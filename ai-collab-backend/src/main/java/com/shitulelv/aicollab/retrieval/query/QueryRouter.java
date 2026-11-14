package com.shitulelv.aicollab.retrieval.query;

import com.shitulelv.aicollab.retrieval.queryplan.QueryPlanGatewayClient;
import com.shitulelv.aicollab.retrieval.queryplan.QueryPlanGatewayException;
import com.shitulelv.aicollab.retrieval.queryplan.QueryPlanProperties;
import com.shitulelv.aicollab.retrieval.queryplan.QueryPlanRequest;
import com.shitulelv.aicollab.retrieval.queryplan.QueryPlanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Heuristic and rule-based query router.
 * Evaluates user queries to determine complexity and appropriate retrieval pipeline:
 * - FAST: Short, single-intent direct lookups (bypasses heavy multi-stage rerank).
 * - STRUCTURED: Queries with explicit metadata constraints (time, type, file, heading).
 * - COMPLEX: Comparative, multi-subject, or composite questions decomposed into subqueries.
 * - HYBRID: Standard single-intent semantic questions routed dense-first with bounded fallback
 *   (Dense-only unless the assembled evidence is insufficient; exact-identifier queries keep the
 *   lexical channel via HYBRID).
 *
 * Routing precedence (order is contractual): STRUCTURED → COMPLEX → EXACT_IDENTIFIER → FAST →
 * default dense-first. Exact-identifier detection precedes FAST so that short identifiers
 * (e.g. "JWT") are not intercepted by the dense fast path.
 *
 * All decisions provide explicit trace reasons for auditability; failure logs are masked and
 * never contain the raw user query. In case of any routing errors, safely falls back to HYBRID.
 *
 * 注册为 Spring Bean（RAG_V2_ROUTING_CLOSURE-01 修复）：此前缺少 @Component，
 * 生产装配中 {@code @Autowired(required=false)} 注入为 null，策略路由在 QA/流式/Agent
 * 链路实际未生效（decision=null 恒走完整 HYBRID 管线）。
 */
@Component
public class QueryRouter {
    private static final Logger log = LoggerFactory.getLogger(QueryRouter.class);

    private static final int FAST_MAX_CODE_POINTS = 20;
    /** 日志脱敏：仅保留前缀长度（与 StrategyAwareRetrievalExecutor 掩码口径一致）。 */
    private static final int MASKED_LOG_CHARS = 6;

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

    @Autowired(required = false)
    private QueryPlanGatewayClient queryPlanGatewayClient;
    @Autowired(required = false)
    private QueryPlanProperties queryPlanProperties;

    static boolean containsExactIdentifier(String query) {
        return query != null && !query.isBlank() && EXACT_IDENTIFIER_PATTERN.matcher(query).find();
    }

    /** 日志掩码：仅保留固定前缀长度，防止用户查询全文进入日志。 */
    private static String mask(String text) {
        if (text == null || text.isBlank()) return "";
        return text.substring(0, Math.min(MASKED_LOG_CHARS, text.length())) + "***";
    }

    public QueryRouter() {
        this(new MetadataFilterParser(), new MultiQueryDecomposer());
    }

    public QueryRouter(MetadataFilterParser filterParser, MultiQueryDecomposer decomposer) {
        this(filterParser, decomposer, null, null);
    }

    QueryRouter(MetadataFilterParser filterParser, MultiQueryDecomposer decomposer,
            QueryPlanGatewayClient queryPlanGatewayClient, QueryPlanProperties queryPlanProperties) {
        this.filterParser = filterParser != null ? filterParser : new MetadataFilterParser();
        this.decomposer = decomposer != null ? decomposer : new MultiQueryDecomposer();
        this.queryPlanGatewayClient = queryPlanGatewayClient;
        this.queryPlanProperties = queryPlanProperties;
    }

    /**
     * Determines the complexity and routing strategy for the incoming user query.
     *
     * @param rawQuery the raw query string from the user
     * @return QueryRouteDecision containing complexity, reason, structuredQuery, and subqueries
     */
    public QueryRouteDecision route(String rawQuery) {
        return route(rawQuery, List.of());
    }

    /**
     * 路由查询，并把调用方已限定的文档范围作为 QueryPlan 的去重输入。
     * 文档范围仍由检索门面与管线强制校验，计划结果不得扩大调用方范围。
     */
    public QueryRouteDecision route(String rawQuery, List<UUID> documentIds) {
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
                List<String> localSubQueries = decomposer.decompose(semanticQuery);
                String localReason = determineComplexReason(semanticQuery, localSubQueries);
                return routeComplexWithOptionalQueryPlan(
                        semanticQuery, localReason, structuredQuery, localSubQueries, documentIds);
            }

            // 3. Exact-identifier detection precedes FAST: short identifiers (e.g. "JWT") must not
            // be intercepted by the dense fast path — lexical exact matching has verifiable value here.
            // 仅识别结构特征（错误码/版本号/文件名/全大写代号），并非所有含标识符的问题都改走 HYBRID：
            // COMPLEX/STRUCTURED 优先级更高，命中本分支的是“单一意图 + 结构化标识符”这一窄类。
            if (containsExactIdentifier(semanticQuery)) {
                return QueryRouteDecision.hybridWithStrategy(
                        "Exact identifier structure detected (error code/version/file name); "
                                + "lexical exact matching retained in HYBRID",
                        structuredQuery, RetrievalStrategy.HYBRID);
            }

            // 4. Fast lookup query detection (non-identifier short direct lookups)
            if (isFastQuery(semanticQuery)) {
                String reason = "Short direct single-intent lookup suitable for fast single-path retrieval";
                return QueryRouteDecision.fast(reason, structuredQuery);
            }

            // 5. Default: standard single-intent semantic question → dense-first with bounded evidence
            // fallback. DuRetrieval 全量语料实测 Dense-only 优于无条件 Hybrid（Recall@10 0.809 vs 0.729）
            // 且快 7 倍；证据不足时由执行器回退完整管线（最多一次），不牺牲证据覆盖。
            return QueryRouteDecision.hybridWithStrategy(
                    "Standard semantic question routed dense-first with bounded evidence fallback "
                            + "(lexical skipped unless assembled evidence is insufficient)",
                    structuredQuery, RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK);

        } catch (Exception e) {
            // 日志脱敏（RAG_V2_ROUTING_CLOSURE-01 任务三）：不输出原始用户查询；
            // 异常消息可能内嵌输入片段（如非法过滤字段名），仅记录异常类型。
            log.warn("Query routing failed, safely falling back to HYBRID: queryMasked={} queryLen={} errorType={}",
                    mask(rawQuery), rawQuery.length(), e.getClass().getSimpleName());
            StructuredQuery fallbackQuery = new StructuredQuery(rawQuery, List.of());
            return QueryRouteDecision.hybrid(
                    "Fallback to HYBRID due to routing error: " + e.getClass().getSimpleName(),
                    fallbackQuery
            );
        }
    }

    private QueryRouteDecision routeComplexWithOptionalQueryPlan(
            String semanticQuery,
            String localReason,
            StructuredQuery structuredQuery,
            List<String> localSubQueries,
            List<UUID> documentIds) {
        QueryRouteDecision localDecision =
                QueryRouteDecision.complex(localReason, structuredQuery, localSubQueries);
        if (queryPlanProperties == null || !queryPlanProperties.enabled()) {
            log.debug("rag.query_plan.outcome outcome=DISABLED");
            return localDecision;
        }
        if (queryPlanGatewayClient == null) {
            log.warn("rag.query_plan.outcome outcome=UNAVAILABLE reason=CLIENT_MISSING");
            return localDecision;
        }

        QueryPlanResponse plan;
        try {
            plan = queryPlanGatewayClient.plan(new QueryPlanRequest(
                    semanticQuery,
                    "zh-CN",
                    "",
                    documentIds == null ? List.of() : documentIds.stream().map(UUID::toString).toList()));
        } catch (QueryPlanGatewayException failure) {
            log.warn("rag.query_plan.outcome outcome={} reason={}",
                    failure.reason().name(), failure.getClass().getSimpleName());
            return localDecision;
        } catch (RuntimeException failure) {
            log.warn("rag.query_plan.outcome outcome=UNEXPECTED reason={}",
                    failure.getClass().getSimpleName());
            return localDecision;
        }

        if (plan.confidence() < 0.5d) {
            log.info(
                    "rag.query_plan.outcome outcome=LOW_CONFIDENCE confidence={} subQueryCount={}",
                    plan.confidence(), plan.subQueries().size());
            return localDecision;
        }

        List<String> plannedSubQueries = plan.subQueries().isEmpty()
                ? localSubQueries
                : plan.subQueries();
        List<UUID> plannedDocumentIds = validDocumentIds(plan.filters().documentIds());
        List<MetadataFilter> adoptedFilters =
                mergePlanFilters(structuredQuery.filters(), plan.filters());
        String adoptedQuery = plan.rewrittenQuery();
        String reason = localReason + " | QueryPlan adopted: rewritten query and bounded filters";
        QueryRouteDecision adopted = QueryRouteDecision.complex(
                reason,
                StructuredQuery.of(adoptedQuery, adoptedFilters),
                plannedSubQueries,
                plannedDocumentIds);
        log.info(
                "rag.query_plan.outcome outcome=ADOPTED confidence={} subQueryCount={} "
                        + "documentIdCount={} docTypeCountIgnored={} dateRangePresent={}",
                plan.confidence(), plannedSubQueries.size(), plannedDocumentIds.size(),
                plan.filters().docTypes().size(),
                plan.filters().dateRange().from() != null || plan.filters().dateRange().to() != null);
        return adopted;
    }

    private static List<UUID> validDocumentIds(List<String> rawDocumentIds) {
        if (rawDocumentIds == null || rawDocumentIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> unique = new LinkedHashSet<>();
        for (String rawDocumentId : rawDocumentIds) {
            if (rawDocumentId == null || rawDocumentId.isBlank()) {
                continue;
            }
            try {
                unique.add(UUID.fromString(rawDocumentId.strip()));
            } catch (IllegalArgumentException invalidUuid) {
                log.debug("rag.query_plan.outcome outcome=DOCUMENT_ID_IGNORED");
            }
        }
        return List.copyOf(unique);
    }

    private static List<MetadataFilter> mergePlanFilters(
            List<MetadataFilter> localFilters, QueryPlanResponse.Filters planFilters) {
        List<MetadataFilter> merged = new ArrayList<>(
                localFilters == null ? List.of() : localFilters);
        QueryPlanResponse.DateRange dateRange = planFilters == null
                ? QueryPlanResponse.DateRange.empty() : planFilters.dateRange();
        if (dateRange.from() != null && !dateRange.from().isBlank()) {
            merged.add(MetadataFilter.gte("createdAt", dateRange.from()));
        }
        if (dateRange.to() != null && !dateRange.to().isBlank()) {
            merged.add(MetadataFilter.lte("createdAt", dateRange.to()));
        }
        return List.copyOf(merged);
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
