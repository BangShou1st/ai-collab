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
        List<String> subQueries) {

    public QueryRouteDecision {
        complexity = complexity != null ? complexity : QueryComplexity.HYBRID;
        reason = reason != null ? reason : "";
        structuredQuery = structuredQuery != null ? structuredQuery : new StructuredQuery("", List.of());
        subQueries = subQueries != null ? List.copyOf(subQueries) : List.of();
    }

    public static QueryRouteDecision fast(String reason, StructuredQuery query) {
        return new QueryRouteDecision(QueryComplexity.FAST, reason, query, List.of(query.semanticQuery()));
    }

    public static QueryRouteDecision hybrid(String reason, StructuredQuery query) {
        return new QueryRouteDecision(QueryComplexity.HYBRID, reason, query, List.of(query.semanticQuery()));
    }

    public static QueryRouteDecision structured(String reason, StructuredQuery query) {
        return new QueryRouteDecision(QueryComplexity.STRUCTURED, reason, query, List.of(query.semanticQuery()));
    }

    public static QueryRouteDecision complex(String reason, StructuredQuery query, List<String> subQueries) {
        return new QueryRouteDecision(QueryComplexity.COMPLEX, reason, query, subQueries);
    }
}
