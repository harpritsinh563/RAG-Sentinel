package com.ragsentinel.service.chat.impl;

import com.ragsentinel.llmtelemetry.LLMTelemetryExtractor;
import com.ragsentinel.service.RAGTriadEvaluator;
import com.ragsentinel.service.chat.ChatService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;

import static com.ragsentinel.constants.AICustomMetrics.*;
import static com.ragsentinel.constants.AIModelConstants.*;
import static com.ragsentinel.constants.MetricTags.*;

/**
 * This is a simple RAG Chat Service which fetches data from vector DB and calls LLM and returns response
 */
@Service
public class RagChatService implements ChatService {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;
    private final List<LLMTelemetryExtractor> telemetryExtractors;
    private final RAGTriadEvaluator ragTriadEvaluator;
    private final Tracer tracer;

    // We use an AtomicReference so the Gauge can read the latest score dynamically
    private final AtomicReference<Double> latestSimilarityScore = new AtomicReference<>(0.0);

    // Simple heuristic strings for POC
    private static final List<String> CONTEXT_MISS_INDICATORS = List.of("i don't know", "i cannot answer", "not provided in the context", "i am an ai", "as an ai model", "no information available");
    private static Logger log = LoggerFactory.getLogger(RagChatService.class);

    public RagChatService(ChatClient.Builder chatClientBuilder,
                          VectorStore vectorStore,
                          MeterRegistry meterRegistry, List<LLMTelemetryExtractor> llmTelemetryExtractors,
                          RAGTriadEvaluator ragTriadEvaluator,
                          Tracer tracer
                          ) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.meterRegistry = meterRegistry;
        this.telemetryExtractors = llmTelemetryExtractors;
        this.ragTriadEvaluator = ragTriadEvaluator;
        this.tracer = tracer;

        // Register the Gauge once during initialization
        Gauge.builder(VECTOR_TOP_SCORE, latestSimilarityScore, AtomicReference::get)
                .description("The distance score of the top retrieved document")
                .register(meterRegistry);
    }

    @Override
    public String chatWithContext(String prompt) {
        // Retrieve Context & Track Score
        List<Document> documents = performSimilaritySearch(prompt);
        updateTopScoreMetric(documents);
        String context = formatDocuments(documents);

        // Track Context Wastage / Bloat Ratio
        trackWastageRatio(prompt,context);
        trackChunkHitRate(documents);

        // Generate Response & Track LLM Latency
        ChatResponse response = callLlmWithMetrics(prompt, context);
        String finalOutput = response.getResult().getOutput().getContent();

        // Track Usage & Output Guardrails
        recordUsageMetrics(response);
        checkOutputGuardrails(finalOutput);

        // Async evaluation of answer,context relevance and faithfullness
        ragTriadEvaluator.evaluateTriadAsync(prompt,context,finalOutput);

        return finalOutput;
    }

    private List<Document> performSimilaritySearch(String prompt) {
        // 1. Create and start a new Span
        Span searchSpan = tracer.nextSpan().name("rag.vector.search").start();

        // 2. Put the span in scope so logs/downstream calls inherit the Trace ID
        try (Tracer.SpanInScope ws = tracer.withSpan(searchSpan)) {

            List<Document> documents = vectorStore.similaritySearch(prompt);

            // 3. Process our quality metrics and tag the span
            recordRetrievalQualityMetrics(documents, searchSpan);

            return documents;

        } catch (Exception e) {
            searchSpan.error(e);
            throw e;
        } finally {
            // 4. End the span (this automatically calculates and records the latency duration)
            searchSpan.end();
        }
    }

    private void updateTopScoreMetric(List<Document> documents) {
        if (!documents.isEmpty()) {
            // Spring AI PgVector uses "distance" in metadata. Lower is usually better (Cosine Distance).
            Object distanceObj = documents.get(0).getMetadata().get("distance");
            if (distanceObj instanceof Float) {
                latestSimilarityScore.set(((Float) distanceObj).doubleValue());
            } else if (distanceObj instanceof Double) {
                latestSimilarityScore.set((Double) distanceObj);
            }
        }
    }

    private String formatDocuments(List<Document> documents) {
        return documents.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n"));
    }

    private ChatResponse callLlmWithMetrics(String prompt, String context) {
        String augmentedPrompt = String.format("Context: %s\nUser Question: %s", context, prompt);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return chatClient.prompt()
                    .user(augmentedPrompt)
                    .call()
                    .chatResponse();
        } finally {
            sample.stop(meterRegistry.timer(LLM_GENERATION_LATENCY));
        }
    }

    private void recordUsageMetrics(ChatResponse response) {
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            long totalTokens = response.getMetadata().getUsage().getTotalTokens();
            meterRegistry.counter(TOKENS_USAGE, MODEL, PHI3).increment(totalTokens);
        }

        ChatResponseMetadata metadata = response.getMetadata();

        telemetryExtractors.stream()
                .filter(extractor -> extractor.supports(metadata))
                .findFirst()
                .ifPresent(extractor -> extractor.extractAndRecord(metadata));
    }

    private void checkOutputGuardrails(String output) {
        if (output == null || output.isBlank()) {
            return;
        }

        // Strip everything except letters, numbers, spaces, and apostrophes
        String normalizedResponse = output.toLowerCase().replaceAll("[^a-z0-9\\s']", "").trim();
        log.debug("Normalized Response : "+normalizedResponse);

        boolean isHallucination = CONTEXT_MISS_INDICATORS.stream()
                .anyMatch(indicator -> normalizedResponse.contains(indicator.toLowerCase()));

        if (isHallucination) {
            meterRegistry.counter(OUTPUT_GUARDRAIL_TRIPPED, REASON, CONTEXT_MISS).increment();
        }
    }

    private void trackWastageRatio(String prompt, String context) {
        if (context == null || context.isEmpty()) return;

        // Simple character length ratio as a proxy for token bloat
        double ratio = (double) context.length() / prompt.length();

        // DistributionSummary is perfect for tracking ratios/sizes over time
        DistributionSummary.builder(WASTAGE_RATIO)
                .description("Ratio of retrieved context size to original prompt size")
                .register(meterRegistry)
                .record(ratio);
    }

    private void trackChunkHitRate(List<Document> documents) {
        for (Document doc : documents) {
            String chunkSource = (String) doc.getMetadata().getOrDefault("source", doc.getId());

            meterRegistry.counter(CHUNK_RETRIEVAL_COUNT, CHUNK_ID, chunkSource).increment();
        }
    }

    private void recordRetrievalQualityMetrics(List<Document> docs, Span span) {
        if (docs == null || docs.isEmpty()) {
            meterRegistry.counter(RETRIEVAL_EMPTY).increment();
            span.tag(RETRIEVAL_DOCUMENTS_COUNT, "0");
            return;
        }

        span.tag(RETRIEVAL_DOCUMENTS_COUNT, String.valueOf(docs.size()));

        double sum = 0.0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;

        // Pass 1: Find min, max, and sum
        for (Document doc : docs) {
            double distance = extractDistanceSafely(doc);
            sum += distance;
            min = Math.min(min, distance);
            max = Math.max(max, distance);
        }

        double avg = sum / docs.size();

        // Pass 2: Calculate Variance and Standard Deviation
        double varianceSum = 0.0;
        for (Document doc : docs) {
            double distance = extractDistanceSafely(doc);
            varianceSum += Math.pow(distance - avg, 2);
        }
        double stddev = Math.sqrt(varianceSum / docs.size());

        // 1. Attach Attributes to the OpenTelemetry Span
        span.tag(RETRIEVAL_SIMILARITY_AVG, String.format("%.4f", avg));
        span.tag(RETRIEVAL_SIMILARITY_MAX, String.format("%.4f", max));
        span.tag(RETRIEVAL_SIMILARITY_MIN, String.format("%.4f", min));
        span.tag(RETRIEVAL_SIMILARITY_STDDEV, String.format("%.4f", stddev));
        span.tag(RETRIEVAL_EMBEDDING_MODEL, NOMIC_EMBED_TEXT);

        if (!docs.isEmpty()) {
            // Tag the source of the best matching chunk for quick debugging
            span.tag(RETRIEVAL_TOP_SOURCE, (String) docs.get(0).getMetadata().getOrDefault("source", "unknown"));
        }

        // 2. Record Time-Series Metrics for Prometheus/Grafana
        // Using DistributionSummary allows Grafana to calculate percentiles over time
        meterRegistry.summary(RETRIEVAL_DISTANCE_AVG).record(avg);
        meterRegistry.summary(RETRIEVAL_DISTANCE_STDDEV).record(stddev);
        meterRegistry.summary(RETRIEVAL_DISTANCE_SPREAD).record(max - min);
    }

    private double extractDistanceSafely(Document doc) {
        Object distanceObj = doc.getMetadata().get("distance");
        if (distanceObj instanceof Float) {
            return ((Float) distanceObj).doubleValue();
        } else if (distanceObj instanceof Double) {
            return (Double) distanceObj;
        }
        return 0.0; // Fallback if metadata is missing
    }

}