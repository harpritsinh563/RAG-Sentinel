package com.ragsentinel.controller;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ragsentinel.constants.AICustomMetrics.TOKENS_USAGE;
import static com.ragsentinel.constants.EndpointConstants.CHAT;

@RestController
@RequestMapping(CHAT)
public class ChatController {
    private final ChatClient chatClient;
    private final MeterRegistry meterRegistry;

    public ChatController(ChatClient.Builder chatClientBuilder, MeterRegistry meterRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    @PostMapping
    public String generateResponse(@RequestParam String prompt) {

        // String context = vectorStore.similaritySearch(prompt);
        String mockRetrievedContext = "Our company policy states that refunds are processed within 7 business days.";

        // Build the augmented prompt
        String augmentedPrompt = "Context: " + mockRetrievedContext + "\nUser Question: " + prompt;

        // Call the LLM
        ChatResponse response = chatClient.prompt()
                .user(augmentedPrompt)
                .call()
                .chatResponse();

        // Capture Custom Metrics (e.g., Token Cost Tracking)
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            long totalTokens = response.getMetadata().getUsage().getTotalTokens();
            meterRegistry.counter(TOKENS_USAGE, "model", "gpt-4o").increment(totalTokens);
        }

        return response.getResult().getOutput().getContent();
    }
}
