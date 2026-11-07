package com.shitulelv.aicollab.retrieval.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Heuristic and rule-based query router.
 * Evaluates user queries to determine complexity and appropriate retrieval pipeline:
 * - FAST: Short, single-intent direct lookups (bypasses heavy multi-stage rerank).
 * - STRUCTURED: Queries with explicit metadata constraints (time, type, file, heading).
 * - COMPLEX: Comparative, multi-subject, or composite questions decomposed into subqueries.
 * - HYBRID: Standard semantic questions defaulted to dense + lexical hybrid retrieval.
 *
 * All decisions provide explicit trace reasons for auditability.
 * In case of any routing errors, safely falls back to HYBRID.
 */
public class QueryRouter {
    private static final Logger log = LoggerFactory.getLogger(QueryRouter.class);

    private static final int FAST_MAX_CODE_POINTS = 20;

    // Pattern for detecting comparative questions
    private static final Pattern COMPARATIVE_PATTERN = Pattern.compile(
            ".*(?:和|与|跟|及|以及|vs|versus).*?(?:区别|不同点|异同|对比|优缺点|相比|优劣).*",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for detecting composite coordination conjunctions
    private static final Pattern COMPOSITE_CONJUNCTION_PATTERN = Pattern.compile(
            ".*(?:并且|而且|同时|不仅.+?而且|既要.+?又要|一方面.+?另一方面).*"
    );

    // Pattern for FAST query terms / definitions
    private static final Pattern FAST_DEFINITION_PATTERN = Pattern.compile(
            "^(?:什么是|定义|请问什么是|简述)?\\s*[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]{1,12}\\s*(?:是什么|的定义|的端口|的版本|是谁|全称|缩写)?\\??$"
    );

    // Markers that disqualify a query from FAST (indicating need for deep reasoning or explanation)
    private static final Pattern REASONING_DISQUALIFIERS = Pattern.compile(
            ".*(?:为什么|怎么做|如何设计|实施步骤|工作原理|流程分析|详细阐述|优缺点|架构设计).*"
    );

    // 精确标识符结构特征（语言无关）：错误码（ERR-404/HTTP_500）、语义版本号（1.2.3）、
    // 文件名（application.yml、README.md）、全大写代号（JWT）。命中时词法精确匹配有真实收益。
        private static final Pattern EXACT_IDENTIFIER_PATTERN = Pattern.compile(
            "(?:\\b[A-Z]{2,}(?:[-_][A-Z0-9]+)+\\b)"          // 错误码/常量链 ERR-404, HTTP_500
            + "|(?:\\b\\d+(?:\\.\\d+){1,3}\\b)"              // 语义版本号 1.2.3
            + "|(?:\\b[\\w\\-]+\\.[A-Za-z]{1,6}\\b)"          // 文件名 application.yml
            + "|(?:\\b[A-Z][A-Z0-9_]{2,}\\b)");               // 全大写代号 JWT

    private final MetadataFilterParser filterParser;
    private final MultiQueryDecomposer decomposer;

    static boolean containsExactIdentifier(String query) {
        return query != null && !query.isBlank() && EXACT_IDENTIFIER_PATTERN.matcher(query).find();
    }

    public QueryRouter() {
        this(new MetadataFilterParser(), new MultiQueryDecomposer());
    }

    public QueryRouter(MetadataFilterParser filterParser, MultiQueryDecomposer decomposer) {
        this.filterParser = filterParser != null ? filterParser : new MetadataFilterParser();
        this.decomposer = decomposer != null ? decomposer : new MultiQueryDecomposer();
    }

    /**
     * Determines the complexity and routing strategy for the incoming user query.
     *
     * @param rawQuery the raw query string from the user
     * @return QueryRouteDecision containing complexity, reason, structuredQuery, and subqueries
     */
    public QueryRouteDecision route(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return QueryRouteDecision.hybrid(
                    "Empty query defaulted to HYBRID",
                    new StructuredQuery("", List.of())
            );
        }

        try {
            // 1. Structured metadata filter extraction
            StructuredQuery structuredQuery = filterParser.parse(rawQuery);
            if (structuredQuery.hasFilters()) {
                String reason = "User explicitly expressed structured metadata filters: " + structuredQuery.filters();
                return QueryRouteDecision.structured(reason, structuredQuery);
            }

            String semanticQuery = structuredQuery.semanticQuery();

            // 2. Complex query detection (multiple questions, comparison, multi-intent)
            if (isComplexQuery(semanticQuery)) {
                List<String> subQueries = decomposer.decompose(semanticQuery);
                String reason = determineComplexReason(semanticQuery, subQueries);
                return QueryRouteDecision.complex(reason, structuredQuery, subQueries);
            }

            // 3. Fast lookup query detection
            if (isFastQuery(semanticQuery)) {
                String reason = "Short direct single-intent lookup suitable for fast single-path retrieval";
                return QueryRouteDecision.fast(reason, structuredQuery);
            }

            // 4. Default standard hybrid semantic question
            if (containsExactIdentifier(semanticQuery)) {
                return QueryRouteDecision.hybridWithStrategy(
                        "Exact identifier structure detected (error code/version/file name); "
                                + "lexical exact matching retained in HYBRID",
                        structuredQuery, RetrievalStrategy.HYBRID);
            }
            return QueryRouteDecision.hybrid(
                    "Standard semantic question requiring dense vector and lexical hybrid search",
                    structuredQuery
            );

        } catch (Exception e) {
            log.warn("Query routing failed for query '{}', safely falling back to HYBRID: {}", rawQuery, e.getMessage());
            StructuredQuery fallbackQuery = new StructuredQuery(rawQuery, List.of());
            return QueryRouteDecision.hybrid(
                    "Fallback to HYBRID due to routing error: " + e.getMessage(),
                    fallbackQuery
            );
        }
    }

    private boolean isComplexQuery(String query) {
        if (query == null || query.isBlank()) return false;

        // Multiple question marks
        int questionMarks = countQuestionMarks(query);
        if (questionMarks >= 2) {
            return true;
        }

        // Comparative question between multiple entities
        if (COMPARATIVE_PATTERN.matcher(query).matches()) {
            return true;
        }

        // Multi-condition conjunctions
        if (COMPOSITE_CONJUNCTION_PATTERN.matcher(query).matches()) {
            return true;
        }

        return false;
    }

    private boolean isFastQuery(String query) {
        if (query == null || query.isBlank()) return false;
        int codePoints = query.codePointCount(0, query.length());
        if (codePoints > FAST_MAX_CODE_POINTS) {
            return false;
        }

        // If it contains deep reasoning markers, it cannot be FAST
        if (REASONING_DISQUALIFIERS.matcher(query).matches()) {
            return false;
        }

        // Check if matches short definition, property, or term lookup pattern
        return FAST_DEFINITION_PATTERN.matcher(query).matches() || codePoints <= 8;
    }

    private int countQuestionMarks(String query) {
        int count = 0;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '?' || c == '？') {
                count++;
            }
        }
        return count;
    }

    private String determineComplexReason(String query, List<String> subQueries) {
        if (countQuestionMarks(query) >= 2) {
            return "Multiple distinct questions detected in query; decomposed into " + subQueries.size() + " subqueries";
        }
        if (COMPARATIVE_PATTERN.matcher(query).matches()) {
            return "Comparative analysis query detected; decomposed into " + subQueries.size() + " subqueries";
        }
        if (COMPOSITE_CONJUNCTION_PATTERN.matcher(query).matches()) {
            return "Multi-condition conjunction query detected; decomposed into " + subQueries.size() + " subqueries";
        }
        return "Composite complex query decomposed into " + subQueries.size() + " subqueries";
    }
}
