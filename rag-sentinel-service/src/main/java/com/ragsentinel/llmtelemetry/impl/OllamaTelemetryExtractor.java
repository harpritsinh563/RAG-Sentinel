package com.ragsentinel.llmtelemetry.impl;

import com.ragsentinel.llmtelemetry.LLMTelemetryExtractor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct; // Ensure you have the jakarta (or javax) annotation dependency
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

import static com.ragsentinel.constants.AICustomMetrics.*;

@Component
public class OllamaTelemetryExtractor implements LLMTelemetryExtractor {

    private static final Logger log = LoggerFactory.getLogger(OllamaTelemetryExtractor.class);
    private final MeterRegistry meterRegistry;

    // 1. Declare Timers at the class level
    private Timer promptEvalTimer;
    private Timer generationTimer;
    private Timer modelLoadTimer;

    public OllamaTelemetryExtractor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // 2. Pre-Register Metrics on Application Startup
    @PostConstruct
    public void initMetrics() {
        this.promptEvalTimer = Timer.builder(CONTEXT_PROCESSING_TIME)
                .description("Context processing time")
                .register(meterRegistry);

        this.generationTimer = Timer.builder(TOKEN_GENERATION_TIME)
                .description("Token generation time")
                .register(meterRegistry);

        this.modelLoadTimer = Timer.builder(MODEL_LOAD_TIME)
                .description("Model load/queue time")
                .register(meterRegistry);

        log.info("Ollama Timers statically registered with Micrometer.");
    }

    @Override
    public boolean supports(ChatResponseMetadata metadata) {
        return metadata != null && metadata.getClass().getSimpleName().toLowerCase().contains("ollama");
    }

    @Override
    public void extractAndRecord(ChatResponseMetadata metadata) {
        recordDuration(promptEvalTimer, "getPromptEvalDuration", extractDuration(metadata, "getPromptEvalDuration"));
        recordDuration(generationTimer, "getEvalDuration", extractDuration(metadata, "getEvalDuration"));
        recordDuration(modelLoadTimer, "getLoadDuration", extractDuration(metadata, "getLoadDuration"));
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
            // Switched to debug. No need to spam error logs if Ollama omits a specific metric.
            log.debug("Could not extract {} from metadata. Method may not exist.", methodName);
        }
        return null;
    }

    // 3. Safe Recording to the Pre-Registered Timers
    private void recordDuration(Timer timer, String methodName, Duration duration) {
        if (duration != null && !duration.isZero()) {
            timer.record(duration);
        } else {
            log.warn("Ollama metadata missing or zero for: {}", methodName);
        }
    }
}