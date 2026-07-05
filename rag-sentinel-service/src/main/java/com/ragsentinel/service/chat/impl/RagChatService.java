package com.ragsentinel.service.chat.impl;

import com.ragsentinel.constants.GeneralConstants;
import com.ragsentinel.llmtelemetry.LLMTelemetryExtractor;
import com.ragsentinel.prompt.PromptVersionManager;
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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;

import static com.ragsentinel.constants.AICustomMetrics.*;
import static com.ragsentinel.constants.AIModelConstants.*;
import static com.ragsentinel.constants.GeneralConstants.*;
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
    private final PromptVersionManager promptVersionManager;

    // We use an AtomicReference so the Gauge can read the latest score dynamically
    private final AtomicReference<Double> latestSimilarityScore = new AtomicReference<>(0.0);

    // Simple heuristic strings for POC
    private static final List<String> CONTEXT_MISS_INDICATORS = List.of(
            // --- 1. The Classics & Direct Admissions (10) ---
            "i don't know",
            "i do not know",
            "i am not sure",
            "i'm not sure",
            "i cannot answer",
            "i can't answer",
            "i am unable to answer",
            "i have no idea",
            "i cannot determine",
            "i can't determine",

            // --- 2. Explicit Context/Text/Document Absences (15) ---
            "not provided in the context",
            "not mentioned in the context",
            "not explicitly mentioned",
            "not found in the context",
            "not stated in the text",
            "context does not mention",
            "context does not provide",
            "context does not state",
            "provided text does not",
            "provided documents do not",
            "given text does not",
            "no mention of",
            "omitted from the context",
            "absent from the text",
            "information is missing from",

            // --- 3. Polite Refusals & Apologies (10) ---
            "i'm sorry",
            "i am sorry",
            "unfortunately",
            "i apologize",
            "i cannot fulfill",
            "i can't fulfill",
            "beyond the scope of the provided",
            "outside the scope of the context",
            "does not contain information about",
            "unable to assist with this specific",

            // --- 4. AI Boilerplate & Guardrail Guard Phrases (10) ---
            "i am an ai",
            "as an ai",
            "as a large language model",
            "my knowledge base does not",
            "i do not have access to",
            "i don't have access to",
            "no information available",
            "insufficient information",
            "insufficient data",
            "data not provided",

            // --- 5. Test Harness & k6 Specific Catchers (7) ---
            "graphql rate limiting",
            "merchant_refund_approved",
            "system override",
            "ignore previous instructions",
            "ignore all previous instructions",
            "bypass system",
            "override prompt"
    );
    private static Logger log = LoggerFactory.getLogger(RagChatService.class);

    // Add a strict threshold: e.g., if distance > 0.75, the chunk is garbage.
    // Note: For Cosine Distance, lower is better. Adjust based on your embedding model's typical spread.
    private static final double MAX_DISTANCE_THRESHOLD = 0.75;
    private final double[] topVectorScore = new double[1];

    public RagChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, MeterRegistry meterRegistry, List<LLMTelemetryExtractor> llmTelemetryExtractors, RAGTriadEvaluator ragTriadEvaluator, Tracer tracer, PromptVersionManager promptVersionManager) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.meterRegistry = meterRegistry;
        this.telemetryExtractors = llmTelemetryExtractors;
        this.ragTriadEvaluator = ragTriadEvaluator;
        this.tracer = tracer;
        this.promptVersionManager = promptVersionManager;

        // Register the Gauge once during initialization
        // Register the gauge. Prometheus will pull the value from topVectorScore[0] every 15 seconds
        Gauge.builder(VECTOR_TOP_SCORE, () -> topVectorScore[0]).description("Top vector similarity score").register(meterRegistry);
        meterRegistry.counter(GUARDRAIL_VIOLATION).increment(0);
        meterRegistry.counter(OUTPUT_GUARDRAIL_TRIPPED, REASON, CONTEXT_MISS).increment(0);
        meterRegistry.counter(RETRIEVAL_EMPTY).increment(0);
    }

    @Override
    public String chatWithContext(String prompt, String sessionId) {
        // 1. Prepare payload
        RagPayload payload = prepareRagPayload(prompt, sessionId);

        // 2. Sync Execution
        ChatResponse response = callLlmWithMetrics(payload.augmentedPrompt(), sessionId);
        String finalOutput = response.getResult().getOutput().getContent();

        // 3. Sync Post-Processing
        recordUsageMetrics(response.getMetadata(), PromptVersionManager.ACTIVE_VERSION);
        checkOutputGuardrails(finalOutput);
        ragTriadEvaluator.evaluateTriadAsync(prompt, payload.context(), finalOutput);

        meterRegistry.counter("rag.session.interactions", "session_id", sessionId).increment();

        return finalOutput;
    }

    @Override
    public Flux<String> streamChatWithContext(String prompt, String sessionId) {
        // 1. Prepare payload
        RagPayload payload = prepareRagPayload(prompt, sessionId);

        // 2. Async Execution (Streaming)
        return streamLlmWithMetrics(payload.augmentedPrompt(), sessionId, prompt, payload.context());
    }

    private Flux<String> streamLlmWithMetrics(String augmentedPrompt, String sessionId, String originalPrompt, String context) {
        java.util.concurrent.atomic.AtomicBoolean isFirstToken = new java.util.concurrent.atomic.AtomicBoolean(true);
        StringBuilder aggregatedAnswer = new StringBuilder();

        // A thread-safe bucket to catch the metadata when it arrives on the final chunk
        java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.metadata.ChatResponseMetadata> finalMetadata = new java.util.concurrent.atomic.AtomicReference<>();

        return chatClient.prompt().user(augmentedPrompt).stream().chatResponse().transformDeferredContextual((chatResponseFlux, ctx) -> {
            io.micrometer.core.instrument.Timer.Sample ttftSample = io.micrometer.core.instrument.Timer.start(meterRegistry);

            return chatResponseFlux.doOnNext(response -> {
                if (isFirstToken.compareAndSet(true, false)) {
                    ttftSample.stop(meterRegistry.timer("rag.llm.ttft", "session_id", sessionId));
                }

                if (response.getResult() != null && response.getResult().getOutput() != null) {
                    aggregatedAnswer.append(response.getResult().getOutput().getContent());
                }

                // Catch the metadata (Usually only non-null on the final SSE packet)
                if (response.getMetadata() != null) {
                    finalMetadata.set(response.getMetadata());
                }
            }).doOnComplete(() -> {
                String finalAnswer = aggregatedAnswer.toString();
                checkOutputGuardrails(finalAnswer);
                ragTriadEvaluator.evaluateTriadAsync(originalPrompt, context, finalAnswer);
                meterRegistry.counter("rag.session.interactions", "session_id", sessionId).increment();

                // Record our Tokens and Ollama Durations!
                if (finalMetadata.get() != null) {
                    recordUsageMetrics(finalMetadata.get(), PromptVersionManager.ACTIVE_VERSION);
                }
            });
        }).map(response -> response.getResult() != null && response.getResult().getOutput() != null ? response.getResult().getOutput().getContent() : "");
    }

    private List<Document> performSimilaritySearch(String prompt) {
        // Start BOTH the OTel Span and the Micrometer Timer
        Span searchSpan = tracer.nextSpan().name("rag.vector.search").start();
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(meterRegistry);

        try (io.micrometer.tracing.Tracer.SpanInScope ws = tracer.withSpan(searchSpan)) {

            List<Document> documents = vectorStore.similaritySearch(prompt);
            recordRetrievalQualityMetrics(documents, searchSpan);
            return documents;

        } catch (Exception e) {
            searchSpan.error(e);
            throw e;
        } finally {
            searchSpan.end();
            // Stop the timer to populate the Prometheus Latency Stack panel
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
        return documents.stream().map(Document::getContent).collect(Collectors.joining("\n"));
    }

    private ChatResponse callLlmWithMetrics(String prompt, String context) {
        String augmentedPrompt = String.format("Context: %s\nUser Question: %s", context, prompt);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return chatClient.prompt().user(augmentedPrompt).call().chatResponse();
        } finally {
            sample.stop(meterRegistry.timer(LLM_GENERATION_LATENCY));
        }
    }

    // Update the method signature
    private void recordUsageMetrics(org.springframework.ai.chat.metadata.ChatResponseMetadata metadata, String promptVersion) {
        if (metadata == null) {
            return;
        }

        // 1. Extract the native Usage object you saw in the debugger
        var usage = metadata.getUsage();

        if (usage != null) {
            // We use "unknown_model" fallback just in case the model name isn't populated
            String modelName = metadata.getModel() != null && !metadata.getModel().isBlank() ? metadata.getModel() : "phi3";

            // 2. Safely extract the exact integers you spotted!
            long promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            long generationTokens = usage.getGenerationTokens() != null ? usage.getGenerationTokens() : 0;
            long totalTokens = promptTokens + generationTokens;

            // 3. Push them to Prometheus with our specific tags
            if (promptTokens > 0) {
                meterRegistry.counter(RAG_TOKENS_USAGE,
                        TYPE,PROMPT,
                       MODEL, modelName,
                        PROMPT_VERSION, promptVersion).increment(promptTokens);
            }

            if (generationTokens > 0) {
                meterRegistry.counter(RAG_TOKENS_USAGE,
                        TYPE, COMPLETION,
                       MODEL, modelName,
                        PROMPT_VERSION, promptVersion).increment(generationTokens);
            }

            // Optional: Total tokens if you want a single unified counter
            meterRegistry.counter(RAG_TOKENS_USAGE,
                    TYPE, TOTAL,
                   MODEL, modelName,
                    PROMPT_VERSION, promptVersion).increment(totalTokens);
        }

        // 4. Finally, pass the metadata to our extractors to get the hidden durations
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
        log.debug("Normalized Response : " + normalizedResponse);

        boolean isHallucination = CONTEXT_MISS_INDICATORS.stream().anyMatch(indicator -> normalizedResponse.contains(indicator.toLowerCase()));

        if (isHallucination) {
            meterRegistry.counter(OUTPUT_GUARDRAIL_TRIPPED, REASON, CONTEXT_MISS).increment();
        }
    }

    private void trackWastageRatio(String prompt, String context) {
        if (context == null || context.isEmpty()) return;

        // Simple character length ratio as a proxy for token bloat
        double ratio = (double) context.length() / prompt.length();

        // DistributionSummary is perfect for tracking ratios/sizes over time
        DistributionSummary.builder(WASTAGE_RATIO).description("Ratio of retrieved context size to original prompt size").register(meterRegistry).record(ratio);
    }

    private void trackChunkHitRate(List<Document> documents) {
        for (Document doc : documents) {
            String chunkSource = (String) doc.getMetadata().getOrDefault("source", doc.getId());

            meterRegistry.counter(CHUNK_RETRIEVAL_COUNT, CHUNK_ID, chunkSource).increment();
        }
    }

    private void recordRetrievalQualityMetrics(List<Document> docs, Span span) {
        if (docs == null || docs.isEmpty()) {
            topVectorScore[0] = 0.0;
            meterRegistry.counter(RETRIEVAL_EMPTY).increment();
            span.tag(RETRIEVAL_DOCUMENTS_COUNT, "0");
            return;
        }

        Object distanceObj = docs.get(0).getMetadata().get("distance");

        if (distanceObj instanceof Number number) {
            topVectorScore[0] = number.doubleValue();
        }

        span.tag(RETRIEVAL_DOCUMENTS_COUNT, String.valueOf(docs.size()));

        double firstDistance = extractDistanceSafely(docs.get(0));
        double sum = 0.0;
        double min = firstDistance;
        double max = firstDistance;

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

    private List<Document> filterAndTrackChunks(List<Document> docs) {
        int retrievedCount = docs.size();
        List<Document> usedDocs = new ArrayList<>();
        int discardedCount = 0;

        for (Document doc : docs) {
            double distance = extractDistanceSafely(doc);
            if (distance <= MAX_DISTANCE_THRESHOLD) {
                usedDocs.add(doc);
                String chunkId = (String) doc.getMetadata().getOrDefault("id", "unknown_chunk");
                meterRegistry.counter(CHUNK_RETRIEVAL_COUNT, CHUNK_ID, chunkId).increment();

            } else {
                discardedCount++;
            }
        }

        meterRegistry.counter(CONTEXT_CHUNKS, STATE, RETRIEVED).increment(retrievedCount);
        meterRegistry.counter(CONTEXT_CHUNKS, STATE, USED).increment(usedDocs.size());
        meterRegistry.counter(CONTEXT_CHUNKS, STATE, DISCARDED).increment(discardedCount);

        int totalCharacters = usedDocs.stream().mapToInt(doc -> doc.getContent().length()).sum();
        meterRegistry.summary(CONTEXT_PAYLOAD_CHARS).record(totalCharacters);

        return usedDocs;
    }

    private record RagPayload(String augmentedPrompt, String context) {
    }

    private RagPayload prepareRagPayload(String prompt, String sessionId) {
        // 1. Session Tracing
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag("rag.session.id", sessionId);
        }

        // 2. Vector Retrieval & Quality Filtering
        List<Document> retrievedDocuments = performSimilaritySearch(prompt);
        List<Document> usedDocuments = filterAndTrackChunks(retrievedDocuments);
        String context = formatDocuments(usedDocuments);

        // 3. Payload Analytics
        trackWastageRatio(prompt, context);

        // 4. Prompt Engine
        String augmentedPrompt = promptVersionManager.buildAugmentedPrompt(prompt, context);

        return new RagPayload(augmentedPrompt, context);
    }

}