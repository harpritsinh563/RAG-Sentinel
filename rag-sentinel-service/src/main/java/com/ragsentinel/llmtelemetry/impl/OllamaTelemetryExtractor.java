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
import java.util.Map;

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
        return metadata != null && metadata.getModel().toLowerCase().contains("phi3");
    }

    @Override
    public void extractAndRecord(ChatResponseMetadata metadata) {
        Map<String, Object> internalMap = extractHiddenMap(metadata);

        if (!internalMap.isEmpty()) {
            log.debug("Extracting metrics from Ollama internal map...");
            recordDuration(promptEvalTimer, (Duration) internalMap.get("prompt-eval-duration"));
            recordDuration(generationTimer, (Duration) internalMap.get("eval-duration"));
            recordDuration(modelLoadTimer, (Duration) internalMap.get("load-duration"));
        } else {
            log.debug("Ollama internal map was empty or inaccessible.");
        }
    }

    // Pass the pre-registered Timer object directly
    private void recordDuration(Timer timer, Duration duration) {
        // We keep the zero-check removed so that flatline zero load-times are captured
        if (duration != null) {
            timer.record(duration);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractHiddenMap(Object obj) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField("map");
                field.setAccessible(true);
                return (Map<String, Object>) field.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                log.debug("Reflection error accessing map field: {}", e.getMessage());
                break;
            }
        }
        return java.util.Collections.emptyMap();
    }
}