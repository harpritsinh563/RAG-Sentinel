package com.ragsentinel.llmtelemetry.impl;

import com.ragsentinel.llmtelemetry.LLMTelemetryExtractor;
import com.ragsentinel.service.seeder.DataSeeder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.ollama.metadata.OllamaChatUsage;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

import static com.ragsentinel.constants.AICustomMetrics.*;

@Component
public class OllamaTelemetryExtractor implements LLMTelemetryExtractor {

    private Logger log = LoggerFactory.getLogger(OllamaTelemetryExtractor.class);
    private final MeterRegistry meterRegistry;

    public OllamaTelemetryExtractor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean supports(ChatResponseMetadata metadata) {
        // Check if the underlying implementation class is Ollama-specific
        return metadata != null && metadata.getClass().getSimpleName().toLowerCase().contains("ollama");
    }

    @Override
    public void extractAndRecord(ChatResponseMetadata metadata) {
        recordDuration(CONTEXT_PROCESSING_TIME, "Context processing time", extractDuration(metadata, "getPromptEvalDuration"));
        recordDuration(TOKEN_GENERATION_TIME, "Token generation time", extractDuration(metadata, "getEvalDuration"));
        recordDuration(MODEL_LOAD_TIME, "Model load/queue time", extractDuration(metadata, "getLoadDuration"));
    }

    private Duration extractDuration(ChatResponseMetadata metadata, String methodName) {
        try {
            Method method = metadata.getClass().getMethod(methodName);
            Object result = method.invoke(metadata);

            if (result instanceof Duration) {
                return (Duration) result;
            } else if (result instanceof Long) {
                return Duration.ofNanos((Long) result);
            }
        } catch (Exception e) {
            log.error("Exception occurred in extracting duration for : {}",methodName,e);
        }
        return null;
    }

    private void recordDuration(String metricName, String description, Duration duration) {
        if (duration != null) {
            Timer.builder(metricName)
                    .description(description)
                    .register(meterRegistry)
                    .record(duration);
        }
    }
}
