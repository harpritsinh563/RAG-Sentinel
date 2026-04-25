package com.ragsentinel.llmtelemetry;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;

public interface LLMTelemetryExtractor {
    void extractAndRecord(ChatResponseMetadata metadata);
    boolean supports(ChatResponseMetadata metadata);
}
