package com.ragsentinel.constants;

public class AICustomMetrics {
    private AICustomMetrics(){}

    public static final String TOKENS_USAGE = "llm.token.consumption.total";
    public static final String GUARDRAIL_VIOLATION = "rag.guardrail.violation.input";
    public static final String VECTOR_SEARCH_LATENCY = "rag.vector.search.latency";
}
