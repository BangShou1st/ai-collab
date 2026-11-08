package com.shitulelv.aicollab.retrieval.query;

import java.util.List;

/**
 * Encapsulates the routing decision made by QueryRouter, including complexity,
 * explanatory audit reason, structured query parameters, and any decomposed subqueries.
 */
public record QueryRouteDecision(
        QueryComplexity complexity,
        String reason,
        StructuredQuery structuredQuery,
        List<String> subQueries,
        RetrievalStrategy strategy) {

    public QueryRouteDecision {
        complexity = complexity != null ? complexity : QueryComplexity.HYBRID;
        reason = reason != null ? reason : "";
        structuredQuery = structuredQuery != null ? structuredQuery : new StructuredQuery("", List.of());
        subQueries = subQueries != null ? List.copyOf(subQueries) : List.of();
        strategy = strategy != null ? strategy : defaultStrategy(complexity);
    }

    /** 兼容旧 4 参形态：策略由复杂度默认映射。 */
    public QueryRouteDecision(QueryComplexity complexity, String reason,
            StructuredQuery structuredQuery, List<String> subQueries) {
        this(complexity, reason, structuredQuery, subQueries, null);
    }

    /**
     * 复杂度 → 默认检索策略映射（显式策略可覆盖）。
     * RAG_V2_ROUTING_CLOSURE-01 任务一：普通单一意图语义问题（HYBRID 复杂度）默认改为
     * Dense 优先 + 有界证据回退——DuRetrieval 全量语料实测 Dense-only 优于无条件 Hybrid 且快 7 倍；
     * 无条件 Hybrid 不再是普通语义问题的默认执行策略。安全兜底路径（空查询/路由异常）仍显式走 HYBRID。
     */
    public static RetrievalStrategy defaultStrategy(QueryComplexity complexity) {
        return switch (complexity) {
            case FAST -> RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK;
            case STRUCTURED -> RetrievalStrategy.HYBRID;
            case COMPLEX -> RetrievalStrategy.MULTI_QUERY_HYBRID;
            case HYBRID -> RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK;
        };
    }

    public static QueryRouteDecision fast(String reason, StructuredQuery query) {
        return new QueryRouteDecision(QueryComplexity.FAST, reason, query,
                List.of(query.semanticQuery()), RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK);
    }

    public static QueryRouteDecision hybrid(String reason, StructuredQuery query) {
        return new QueryRouteDecision(QueryComplexity.HYBRID, reason, query,
                List.of(query.semanticQuery()), RetrievalStrategy.HYBRID);
    }

    public static QueryRouteDecision hybridWithStrategy(String reason, StructuredQuery query,
            RetrievalStrategy strategy) {
        return new QueryRouteDecision(QueryComplexity.HYBRID, reason, query,
                List.of(query.semanticQuery()), strategy);
    }

    public static QueryRouteDecision structured(String reason, StructuredQuery query) {
        return new QueryRouteDecision(QueryComplexity.STRUCTURED, reason, query,
                List.of(query.semanticQuery()), RetrievalStrategy.HYBRID);
    }

    public static QueryRouteDecision complex(String reason, StructuredQuery query, List<String> subQueries) {
        return new QueryRouteDecision(QueryComplexity.COMPLEX, reason, query, subQueries,
                RetrievalStrategy.MULTI_QUERY_HYBRID);
    }
}
