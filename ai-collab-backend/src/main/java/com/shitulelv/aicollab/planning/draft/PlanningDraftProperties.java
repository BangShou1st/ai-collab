package com.shitulelv.aicollab.planning.draft;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Planning draft shadow 配置（W5 冻结 §2/§5）。
 *
 * <p><b>默认必须“行为不变”</b>：{@code mode=java} 时不构造快照、不发起任何外呼、
 * 不记录任何 planning draft 指标。</p>
 *
 * <p>越界的 deadline / 并发 / 快照字符数按上下界收敛（配置错误不应阻止启动）；
 * 但 {@code mode} 的未知取值必须让启动失败。{@code python} 已由 skeleton router 实现，
 * 仍保持默认 {@code java}。</p>
 *
 * <p>{@code deadlineMs} 上限为 30000：该值会原样写进 Python {@code /v1/planning/draft}
 * 请求，而 Python 契约上限与 Java 内部模型网关 {@code MAX_DEADLINE_MS} 都是 30000；
 * 超过 30000 会让 worker 在契约层 422，shadow 观测被误判为 provider 故障。</p>
 */
@ConfigurationProperties("planning.draft")
public record PlanningDraftProperties(
        @DefaultValue("java") PlanningDraftMode mode,
        @DefaultValue("http://ai-worker-api:9100") String workerBaseUrl,
        @DefaultValue("30000") int deadlineMs,
        @DefaultValue("4") int maxConcurrency,
        @DefaultValue("48000") int snapshotMaxChars) {

    private static final String DEFAULT_WORKER_BASE_URL = "http://ai-worker-api:9100";
    private static final int DEFAULT_DEADLINE_MS = 30000;
    private static final int MIN_DEADLINE_MS = 1;
    private static final int MAX_DEADLINE_MS = 30000;
    private static final int DEFAULT_MAX_CONCURRENCY = 4;
    private static final int MIN_MAX_CONCURRENCY = 1;
    private static final int MAX_MAX_CONCURRENCY = 64;
    private static final int DEFAULT_SNAPSHOT_MAX_CHARS = 48000;
    /** 低于该值时连“一个有界 goal + 一个事实”都装不下。 */
    private static final int MIN_SNAPSHOT_MAX_CHARS = 4000;
    private static final int MAX_SNAPSHOT_MAX_CHARS = 200000;

    public PlanningDraftProperties {
        if (mode == null) {
            mode = PlanningDraftMode.JAVA;
        }
        workerBaseUrl = normalizeBaseUrl(workerBaseUrl);
        deadlineMs = clamp(deadlineMs, MIN_DEADLINE_MS, MAX_DEADLINE_MS, DEFAULT_DEADLINE_MS);
        maxConcurrency = clamp(maxConcurrency, MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY,
                DEFAULT_MAX_CONCURRENCY);
        snapshotMaxChars = clamp(snapshotMaxChars, MIN_SNAPSHOT_MAX_CHARS,
                MAX_SNAPSHOT_MAX_CHARS, DEFAULT_SNAPSHOT_MAX_CHARS);
    }

    /** 默认值，供不依赖 Spring Binder 的代码与测试使用。 */
    public static PlanningDraftProperties defaults() {
        return new PlanningDraftProperties(PlanningDraftMode.JAVA, DEFAULT_WORKER_BASE_URL,
                DEFAULT_DEADLINE_MS, DEFAULT_MAX_CONCURRENCY, DEFAULT_SNAPSHOT_MAX_CHARS);
    }

    private static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? DEFAULT_WORKER_BASE_URL : value;
    }

    /** 越界值按边界收敛（而不是悄悄回退到默认值）。 */
    private static int clamp(int value, int min, int max, int fallback) {
        if (value == Integer.MIN_VALUE || value == Integer.MAX_VALUE) {
            return fallback;
        }
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
