package com.ragsentinel.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.ragsentinel.constants.AICustomMetrics.*;
import static com.ragsentinel.constants.AIModelConstants.MODEL;
import static com.ragsentinel.constants.GeneralConstants.PROMPT_VERSION;
import static com.ragsentinel.constants.GeneralConstants.TYPE;
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

            // 1. Swap .content() for .chatResponse() to extract execution metadata
            ChatResponse chatResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .chatResponse();

            if (chatResponse != null && chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
                String evalResponse = chatResponse.getResult().getOutput().getContent();

                // 2. Pass both the raw response string and the metadata block down
                recordTriadMetrics(evalResponse, chatResponse.getMetadata());
            }

        } catch (Exception e) {
            log.error("Triad evaluation failed: " + e.getMessage());
        }
    }

    private void recordTriadMetrics(String evalResponse, ChatResponseMetadata metadata) {
        log.info("RAW LLM JUDGE OUTPUT: {}", evalResponse);

        // 1. Normalize the text (lowercase, replace commas with spaces)
        String normalized = evalResponse.toLowerCase().replace(",", " ");

        // 2. Split by any amount of whitespace
        String[] tokens = normalized.split("\\s+");

        // 3. Robustly extract the binary values
        List<Integer> parsedScores = new ArrayList<>();
        for (String token : tokens) {
            String cleanToken = token.replaceAll("[^a-z0-9]", "");

            if (cleanToken.equals("1") || cleanToken.equals("yes") || cleanToken.equals("true")) {
                parsedScores.add(1);
            } else if (cleanToken.equals("0") || cleanToken.equals("no") || cleanToken.equals("false")) {
                parsedScores.add(0);
            }
        }

        // 4. Validate and Record
        if (parsedScores.size() >= 3) {
            int faithfulness = parsedScores.get(0);
            int contextRelevance = parsedScores.get(1);
            int answerRelevance = parsedScores.get(2);

            log.info("PARSED SCORES -> Faithfulness: {}, Context: {}, Answer: {}",
                    faithfulness, contextRelevance, answerRelevance);

            meterRegistry.counter(FAITHFULNESS, "status", faithfulness == 1 ? "pass" : "fail").increment();
            meterRegistry.counter(CONTEXT_RELEVANCE, "status", contextRelevance == 1 ? "pass" : "fail").increment();
            meterRegistry.counter(ANSWER_RELEVANCE, "status", answerRelevance == 1 ? "pass" : "fail").increment();

            // --- NEW: Dynamic Judge Token Analytics ---
            if (metadata != null) {
                // Dynamically resolve the model executing the judge evaluation
                String modelName = metadata.getModel();
                if (modelName == null || modelName.isBlank()) {
                    modelName = "unknown_judge_model";
                }

                if (metadata.getUsage() != null) {
                    long judgeTokens = metadata.getUsage().getTotalTokens();

                    // Increment using the shared "rag.tokens.usage" metric name but with a unique type tag
                    meterRegistry.counter(RAG_TOKENS_USAGE,
                            TYPE, "judge_eval",
                            MODEL, modelName,
                            PROMPT_VERSION, "judge_v1").increment(judgeTokens);
                }
            }

        } else {
            log.error("Triad Evaluation Failed. Could not extract 3 scores from: {}", evalResponse);
        }
    }
}
