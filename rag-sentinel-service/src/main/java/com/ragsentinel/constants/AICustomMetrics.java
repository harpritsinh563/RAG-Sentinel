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
    public static final String GUARDRAIL_VIOLATION = "rag.guardrail.violation";

    // Retrieval
    public static final String RETRIEVAL_SIMILARITY = "rag.retrieval.similarity";
    public static final String RETRIEVAL_SIMILARITY_AVG = RETRIEVAL_SIMILARITY+".avg";
    public static final String RETRIEVAL_SIMILARITY_MAX = RETRIEVAL_SIMILARITY+".max";
    public static final String RETRIEVAL_SIMILARITY_MIN = RETRIEVAL_SIMILARITY+".min";
    public static final String RETRIEVAL_SIMILARITY_STDDEV = RETRIEVAL_SIMILARITY+".stddev";
    public static final String RETRIEVAL_EMBEDDING_MODEL = "retrieval.embedding.model";
    public static final String RETRIEVAL_DISTANCE = "rag.retrieval.distance";
    public static final String RETRIEVAL_DISTANCE_AVG = RETRIEVAL_DISTANCE+".avg";
    public static final String RETRIEVAL_DISTANCE_STDDEV = RETRIEVAL_DISTANCE+".stddev";
    public static final String RETRIEVAL_DISTANCE_SPREAD = RETRIEVAL_DISTANCE+".spread";
    public static final String RETRIEVAL_DOCUMENTS_COUNT = "retrieval.documents.count";
    public static final String RETRIEVAL_EMPTY = "rag.retrieval.empty";
    public static final String RETRIEVAL_TOP_SOURCE = "retrieval.top_source";
}
