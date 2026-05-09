package com.ragsentinel.service.chat.impl;

import com.ragsentinel.llmtelemetry.LLMTelemetryExtractor;
import com.ragsentinel.service.RAGTriadEvaluator;
import com.ragsentinel.service.chat.ChatService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
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
import static com.ragsentinel.constants.AIModelConstants.MODEL;
import static com.ragsentinel.constants.AIModelConstants.PHI3;
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

    // We use an AtomicReference so the Gauge can read the latest score dynamically
    private final AtomicReference<Double> latestSimilarityScore = new AtomicReference<>(0.0);

    // Simple heuristic strings for POC
    private static final List<String> CONTEXT_MISS_INDICATORS = List.of("i don't know", "i cannot answer", "not provided in the context", "i am an ai", "as an ai model", "no information available");
    private static Logger log = LoggerFactory.getLogger(RagChatService.class);

    public RagChatService(ChatClient.Builder chatClientBuilder,
                          VectorStore vectorStore,
                          MeterRegistry meterRegistry, List<LLMTelemetryExtractor> llmTelemetryExtractors,
                          RAGTriadEvaluator ragTriadEvaluator
                          ) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.meterRegistry = meterRegistry;
        this.telemetryExtractors = llmTelemetryExtractors;
        this.ragTriadEvaluator = ragTriadEvaluator;

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
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return vectorStore.similaritySearch(prompt);
        } finally {
            sample.stop(meterRegistry.timer(VECTOR_SEARCH_LATENCY));
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
}