package com.ragsentinel.constants;

public class AICustomMetrics {
    private AICustomMetrics(){}
    public static final String VECTOR_SEARCH_LATENCY = "rag.vector.search.latency";
    public static final String LLM_GENERATION_LATENCY = "llm.generation.latency";
    public static final String TOKENS_USAGE = "llm.token.consumption.total";
    public static final String VECTOR_TOP_SCORE = "rag.vector.top_score";
    public static final String OUTPUT_GUARDRAIL_TRIPPED = "rag.guardrail.output.tripped";
}
