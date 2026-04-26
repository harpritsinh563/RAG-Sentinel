package com.ragsentinel.constants;

public class AICustomMetrics {
    private AICustomMetrics(){}
    public static final String VECTOR_SEARCH_LATENCY = "rag.vector.search.latency";
    public static final String LLM_GENERATION_LATENCY = "llm.generation.latency";
    public static final String TOKENS_USAGE = "llm.token.consumption.total";
    public static final String VECTOR_TOP_SCORE = "rag.vector.top_score";
    public static final String OUTPUT_GUARDRAIL_TRIPPED = "rag.guardrail.output.tripped";
    public static final String CONTEXT_PROCESSING_TIME = "llm.duration.prompt_eval";
    public static final String TOKEN_GENERATION_TIME = "llm.duration.generation";
    public static final String MODEL_LOAD_TIME = "llm.duration.load";
    public static final String WASTAGE_RATIO = "rag.context.wastage_ratio";
    public static final String FAITHFULNESS = "rag.eval.faithfulness";
    public static final String ANSWER_RELEVANCE = "rag.eval.answer_relevance";
    public static final String CONTEXT_RELEVANCE = "rag.eval.context_relevance";
    public static final String CHUNK_RETRIEVAL_COUNT = "rag.chunk.retrieval.count";
}
