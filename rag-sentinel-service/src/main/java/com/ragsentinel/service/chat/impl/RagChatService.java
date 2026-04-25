package com.ragsentinel.service.chat.impl;

import com.ragsentinel.service.chat.ChatService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import static com.ragsentinel.constants.AICustomMetrics.TOKENS_USAGE;
import static com.ragsentinel.constants.AICustomMetrics.VECTOR_SEARCH_LATENCY;
import static com.ragsentinel.constants.AIModelConstants.MODEL;
import static com.ragsentinel.constants.AIModelConstants.PHI3;

/**
 * This is a simple RAG Chat Service which fetches data from vector DB and calls LLM and returns response
 */
@Service
public class RagChatService implements ChatService {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;

    public RagChatService(ChatClient.Builder chatClientBuilder,
                          VectorStore vectorStore,
                          MeterRegistry meterRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String chatWithContext(String prompt) {
        // 1. Retrieve Context
        List<Document> documents = performSimilaritySearch(prompt);
        String context = formatDocuments(documents);

        // 2. Generate Response
        ChatResponse response = callLlm(prompt, context);

        // 3. Track Metrics
        recordUsageMetrics(response);

        return response.getResult().getOutput().getContent();
    }

    private List<Document> performSimilaritySearch(String prompt) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return vectorStore.similaritySearch(prompt);
        } finally {
            sample.stop(meterRegistry.timer(VECTOR_SEARCH_LATENCY));
        }
    }

    private String formatDocuments(List<Document> documents) {
        return documents.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n"));
    }

    private ChatResponse callLlm(String prompt, String context) {
        String augmentedPrompt = String.format("Context: %s\nUser Question: %s", context, prompt);
        return chatClient.prompt()
                .user(augmentedPrompt)
                .call()
                .chatResponse();
    }

    private void recordUsageMetrics(ChatResponse response) {
        if (response != null && response.getMetadata().getUsage() != null) {
            long totalTokens = response.getMetadata().getUsage().getTotalTokens();
            meterRegistry.counter(TOKENS_USAGE, MODEL, PHI3).increment(totalTokens);
        }
    }
}
