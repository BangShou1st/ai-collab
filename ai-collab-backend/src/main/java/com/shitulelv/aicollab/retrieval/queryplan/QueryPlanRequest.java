package com.shitulelv.aicollab.retrieval.queryplan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 发往 Python {@code POST /v1/rag/plan} 的调用参数（W4 冻结契约 §1）。
 *
 * <p>{@code schemaVersion} 与最终生效的 {@code deadlineMs} 由
 * {@link QueryPlanGatewayClient} 按契约补齐：{@code deadlineMs} 为 null 时使用
 * {@code query.plan.deadline-ms}，非 null 时按 1..30000 收敛。</p>
 *
 * <p>本波没有生产调用方；{@code enabled} 默认 false。</p>
 */
public record QueryPlanRequest(
        String query,
        String locale,
        String historySummary,
        List<String> documentIds,
        Integer deadlineMs) {

    private static final String DEFAULT_LOCALE = "zh-CN";
    private static final int MAX_QUERY_CHARS = 4000;
    private static final int MAX_HISTORY_CHARS = 8000;
    private static final int MAX_DOCUMENT_IDS = 200;
    private static final int MAX_DOCUMENT_ID_CHARS = 128;
    private static final int MIN_DEADLINE_MS = 1;
    private static final int MAX_DEADLINE_MS = 30000;
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*");

    public QueryPlanRequest {
        query = query == null ? "" : query.strip();
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("query 必须为 1–4000 字符");
        }
        locale = locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale.strip();
        if (!LOCALE.matcher(locale).matches()) {
            throw new IllegalArgumentException("locale 不匹配 BCP-47 形状约束");
        }
        historySummary = historySummary == null ? "" : historySummary;
        if (historySummary.length() > MAX_HISTORY_CHARS) {
            throw new IllegalArgumentException("historySummary 不能超过 8000 字符");
        }
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        if (documentIds.size() > MAX_DOCUMENT_IDS) {
            throw new IllegalArgumentException("documentIds 不能超过 200 项");
        }
        Set<String> unique = new HashSet<>();
        for (String documentId : documentIds) {
            if (documentId == null || documentId.isBlank()
                    || documentId.strip().length() > MAX_DOCUMENT_ID_CHARS) {
                throw new IllegalArgumentException("documentIds 每项必须为 1–128 字符");
            }
            if (!unique.add(documentId)) {
                throw new IllegalArgumentException("documentIds 同一请求内唯一");
            }
        }
        if (deadlineMs != null
                && (deadlineMs < MIN_DEADLINE_MS || deadlineMs > MAX_DEADLINE_MS)) {
            throw new IllegalArgumentException("deadlineMs 必须在 1..30000 之间");
        }
    }

    /** 只读调用便捷构造：deadline 使用 {@code query.plan.deadline-ms}。 */
    public QueryPlanRequest(String query, String locale, String historySummary,
            List<String> documentIds) {
        this(query, locale, historySummary, documentIds, null);
    }
}
