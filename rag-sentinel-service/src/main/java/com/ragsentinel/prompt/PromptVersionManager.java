package com.ragsentinel.prompt;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class PromptVersionManager {

    private final MeterRegistry meterRegistry;

    // Core Prompt Version Definition
    public static final String ACTIVE_VERSION = "v1.2.0";

    private static final String SYSTEM_INSTRUCTIONS = """
        You are an advanced software operations assistant evaluating infrastructure runtime parameters.
        Answer the User Query strictly utilizing the provided Context block.
        If the context does not contain the answer, follow standard fallback mitigation protocols.
        
        Context Block:
        {context}
        """;

    public PromptVersionManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public String buildAugmentedPrompt(String userPrompt, String context) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SystemPromptTemplate template = new SystemPromptTemplate(SYSTEM_INSTRUCTIONS);
            Prompt generatedPrompt = template.create(Map.of("context", context));

            // Compose clean operational payload
            return String.format("%s\nUser Query: %s", generatedPrompt.getContents(), userPrompt);
        } finally {
            sample.stop(meterRegistry.timer("rag.prompt.construction.latency", "version", ACTIVE_VERSION));
        }
    }
}
