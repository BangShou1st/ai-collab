package com.shitulelv.aicollab.retrieval.queryplan;

import java.util.List;

/**
 * Python {@code POST /v1/rag/plan} 的解析结果（W4 冻结契约 §1）。
 *
 * <p>{@code filters} 只可能有 {@code documentIds}/{@code docTypes}/{@code dateRange}
 * 三类键；未知键在客户端解析阶段即按契约违规拒绝，不静默丢弃。{@code dateRange}
 * 的两端为 ISO-8601 日期字符串（可空）。</p>
 */
public record QueryPlanResponse(
        int schemaVersion,
        String rewrittenQuery,
        List<String> subQueries,
        Filters filters,
        double confidence) {

    public QueryPlanResponse {
        subQueries = subQueries == null ? List.of() : List.copyOf(subQueries);
        filters = filters == null ? Filters.empty() : filters;
    }

    /** 三类过滤键；未给出的键为空列表/null 日期端。 */
    public record Filters(List<String> documentIds, List<String> docTypes, DateRange dateRange) {
        public Filters {
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            docTypes = docTypes == null ? List.of() : List.copyOf(docTypes);
            dateRange = dateRange == null ? DateRange.empty() : dateRange;
        }

        public static Filters empty() {
            return new Filters(List.of(), List.of(), DateRange.empty());
        }
    }

    /** ISO-8601 日期区间，两端可空；客户端保证 from<=to。 */
    public record DateRange(String from, String to) {
        public static DateRange empty() {
            return new DateRange(null, null);
        }
    }
}
