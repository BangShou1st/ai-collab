package com.shitulelv.aicollab.retrieval.queryplan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * QueryPlan 客户端配置（W4 冻结契约 §4）。
 *
 * <p>{@code enabled} 默认 false：本波只交付客户端与 DTO 契约，<b>不接入检索管线</b>，
 * 也没有任何生产调用方；开关只为后续接线预留。越界 deadline 按 1..30000 收敛。</p>
 */
@ConfigurationProperties("query.plan")
public record QueryPlanProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://ai-worker-api:9100") String workerBaseUrl,
        @DefaultValue("8000") int deadlineMs) {

    private static final String DEFAULT_WORKER_BASE_URL = "http://ai-worker-api:9100";
    private static final int DEFAULT_DEADLINE_MS = 8000;
    private static final int MIN_DEADLINE_MS = 1;
    private static final int MAX_DEADLINE_MS = 30000;

    public QueryPlanProperties {
        workerBaseUrl = normalizeBaseUrl(workerBaseUrl);
        if (deadlineMs < MIN_DEADLINE_MS) {
            deadlineMs = MIN_DEADLINE_MS;
        } else if (deadlineMs > MAX_DEADLINE_MS) {
            deadlineMs = MAX_DEADLINE_MS;
        }
    }

    /** 默认值，供不依赖 Spring Binder 的代码与测试使用。 */
    public static QueryPlanProperties defaults() {
        return new QueryPlanProperties(false, DEFAULT_WORKER_BASE_URL, DEFAULT_DEADLINE_MS);
    }

    private static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? DEFAULT_WORKER_BASE_URL : value;
    }
}
