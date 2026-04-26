package com.ragsentinel.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import static com.ragsentinel.constants.AICustomMetrics.*;
import static com.ragsentinel.constants.MetricTags.STATUS;

/**
 * This is a simple implementation to evaluate faithfulness, answer relevance, context relevance using LLM-As-A-Judge technique
 */
@Service
public class RAGTriadEvaluator {
    private final ChatClient chatClient;
    private final MeterRegistry meterRegistry;
    private Logger log = LoggerFactory.getLogger(RAGTriadEvaluator.class);

    // The One-Shot Triad Prompt
    private static final String TRIAD_EVAL_PROMPT = """
            You are a strict grading system evaluating a RAG pipeline.
            Evaluate the following based on three criteria. 
            Output STRICTLY a comma-separated list of three numbers (1 for Yes, 0 for No). No other text.

            1. Faithfulness: Is the ANSWER strictly based on the CONTEXT?
            2. Context Relevance: Does the CONTEXT contain the necessary information to answer the USER QUESTION?
            3. Answer Relevance: Does the ANSWER directly address the USER QUESTION?

            USER QUESTION:
            {question}

            CONTEXT:
            {context}

            ANSWER:
            {answer}

            OUTPUT FORMAT EXAMPLE: 1,0,1
            """;

    public RAGTriadEvaluator(ChatClient.Builder chatClientBuilder, MeterRegistry meterRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    @Async
    public void evaluateTriadAsync(String userQuestion, String context, String finalAnswer) {
        if (userQuestion == null || context == null || finalAnswer == null) return;

        try {
            String prompt = TRIAD_EVAL_PROMPT
                    .replace("{question}", userQuestion)
                    .replace("{context}", context)
                    .replace("{answer}", finalAnswer);

            String evalResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            recordTriadMetrics(evalResponse);

        } catch (Exception e) {
            log.error("Triad evaluation failed: " + e.getMessage());
        }
    }

    private void recordTriadMetrics(String evalResponse) {
        if (evalResponse == null) return;

        // Clean up the response (e.g., " 1, 0, 1 " -> "1,0,1")
        String cleanResponse = evalResponse.replaceAll("[^0,1]", "").trim();
        String[] scores = cleanResponse.split(",");

        if (scores.length == 3) {
            // Metric 1: Faithfulness
            meterRegistry.counter(FAITHFULNESS, STATUS, "1".equals(scores[0]) ? "pass" : "fail").increment();

            // Metric 2: Context Relevance
            meterRegistry.counter(CONTEXT_RELEVANCE, STATUS, "1".equals(scores[1]) ? "pass" : "fail").increment();

            // Metric 3: Answer Relevance
            meterRegistry.counter(ANSWER_RELEVANCE, STATUS, "1".equals(scores[2]) ? "pass" : "fail").increment();
        }
    }
}
