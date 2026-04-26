package com.ragsentinel.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import static com.ragsentinel.constants.AICustomMetrics.FAITHFULNESS;
import static com.ragsentinel.constants.MetricTags.STATUS;

/**
 * This is a simple implementation to evaluate faithfulness using LLM-As-A-Judge technique
 */
@Service
public class FaithfulnessEvaluator {
    private final ChatClient chatClient;
    private final MeterRegistry meterRegistry;
    private Logger log = LoggerFactory.getLogger(FaithfulnessEvaluator.class);

    // A strict prompt designed to force the LLM to output ONLY a 1 or a 0
    private static final String EVAL_PROMPT = """
            You are a strict grading system. 
            Evaluate if the following ANSWER is strictly based on the CONTEXT. 
            If the ANSWER contains facts not present in the CONTEXT, it is unfaithful.
            
            CONTEXT:
            {context}
            
            ANSWER:
            {answer}
            
            Reply strictly with a single number:
            1 (if completely faithful to the context)
            0 (if unfaithful or hallucinated)
            """;

    public FaithfulnessEvaluator(ChatClient.Builder chatClientBuilder, MeterRegistry meterRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    @Async
    public void evaluateFaithfulnessAsync(String context, String finalAnswer) {
        if (context == null || context.isBlank() || finalAnswer == null || finalAnswer.isBlank()) {
            return;
        }

        try {
            String prompt = EVAL_PROMPT.replace("{context}", context).replace("{answer}", finalAnswer);

            // Execute the evaluation call
            String evalResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // Parse the score safely
            recordMetric(evalResponse);
        } catch (Exception e) {
            // In an async background task, we log and swallow the exception so we don't crash
            log.error("Faithfulness evaluation failed: " + e.getMessage());
        }
    }

    private void recordMetric(String evalResponse) {
        if (evalResponse == null) return;

        // Clean the output in case the LLM adds a period or whitespace (e.g., "1." or " 0 ")
        String cleanResponse = evalResponse.replaceAll("[^0-1]", "").trim();

        if ("1".equals(cleanResponse)) {
            meterRegistry.counter(FAITHFULNESS, STATUS, "faithful").increment();
        } else if ("0".equals(cleanResponse)) {
            meterRegistry.counter(FAITHFULNESS, STATUS, "hallucinated").increment();
        }
    }
}
