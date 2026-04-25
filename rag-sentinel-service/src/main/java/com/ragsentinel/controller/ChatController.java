package com.ragsentinel.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

import static com.ragsentinel.constants.AICustomMetrics.TOKENS_USAGE;
import static com.ragsentinel.constants.AICustomMetrics.VECTOR_SEARCH_LATENCY;
import static com.ragsentinel.constants.EndpointConstants.CHAT;

@RestController
@RequestMapping(CHAT)
public class ChatController {
    private final ChatClient chatClient;
    private final MeterRegistry meterRegistry;
    private final VectorStore vectorStore;

    public ChatController(ChatClient.Builder chatClientBuilder, MeterRegistry meterRegistry, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.meterRegistry = meterRegistry;
        this.vectorStore = vectorStore;

        // Simple mock document for POC
        vectorStore.add(List.of(new Document("Our company policy states that refunds are processed within 7 business days, and require manager approval for amounts over $500.")));
    }

    @PostMapping
    public String generateResponse(@RequestParam String prompt) {

        // Measure Vector DB Latency using a Timer
        Timer.Sample sample = Timer.start(meterRegistry);
        List<Document> similarDocuments = vectorStore.similaritySearch(prompt);
        sample.stop(meterRegistry.timer(VECTOR_SEARCH_LATENCY));

        // Extract text from the retrieved documents
        String retrievedContext = similarDocuments.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n"));

        // Build the augmented prompt
        String augmentedPrompt = "Context: " + retrievedContext + "\nUser Question: " + prompt;

        // Call the LLM
        ChatResponse response = chatClient.prompt()
                .user(augmentedPrompt)
                .call()
                .chatResponse();

        // Capture Custom Metrics (e.g. Token Cost Tracking)
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            long totalTokens = response.getMetadata().getUsage().getTotalTokens();
            meterRegistry.counter(TOKENS_USAGE, "model", "gpt-4o").increment(totalTokens);
        }

        return response.getResult().getOutput().getContent();
    }
}
