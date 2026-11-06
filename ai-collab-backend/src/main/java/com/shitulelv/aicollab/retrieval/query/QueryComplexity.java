package com.shitulelv.aicollab.retrieval.query;

/**
 * Complexity levels for routing user retrieval queries to appropriate retrieval pipelines.
 */
public enum QueryComplexity {
    /**
     * Simple, direct, short single-intent query (e.g. term lookup or definition).
     * Bypasses heavy multi-stage recall or complex reranking.
     */
    FAST,

    /**
     * Standard semantic Q&A query, routed to dense vector + lexical hybrid retrieval.
     */
    HYBRID,

    /**
     * Multi-intent, comparative, or composite questions requiring multi-query decomposition.
     */
    COMPLEX,

    /**
     * Query with explicit structured metadata filters (filename, mimeType, time, section).
     */
    STRUCTURED
}
