package com.shitulelv.aicollab.internal.ai.index;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Python 索引管理客户端与定期 PRUNE 配置（P2 契约 §4.6、§12.6 R4-2/R4-3）。
 *
 * <p>超界参数收敛到安全区间，避免配置误写阻断启动。</p>
 */
@ConfigurationProperties("ai.index")
public record AiIndexProperties(
        @DefaultValue("http://ai-worker:9100") String workerBaseUrl,
        @DefaultValue("30000") int requestTimeoutMs,
        @DefaultValue("PT6H") Duration pruneInterval,
        @DefaultValue("PT5M") Duration pruneInitialDelay) {

    private static final String DEFAULT_WORKER_BASE_URL = "http://ai-worker:9100";
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int MIN_TIMEOUT_MS = 1_000;
    private static final int MAX_TIMEOUT_MS = 120_000;
    private static final Duration DEFAULT_PRUNE_INTERVAL = Duration.ofHours(6);
    private static final Duration DEFAULT_PRUNE_INITIAL_DELAY = Duration.ofMinutes(5);

    public AiIndexProperties {
        workerBaseUrl = workerBaseUrl == null || workerBaseUrl.isBlank()
                ? DEFAULT_WORKER_BASE_URL : workerBaseUrl.strip().replaceAll("/+$", "");
        if (requestTimeoutMs <= 0) {
            requestTimeoutMs = DEFAULT_TIMEOUT_MS;
        }
        requestTimeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(requestTimeoutMs, MAX_TIMEOUT_MS));
        pruneInterval = pruneInterval == null || pruneInterval.isNegative() || pruneInterval.isZero()
                ? DEFAULT_PRUNE_INTERVAL : pruneInterval;
        pruneInitialDelay = pruneInitialDelay == null || pruneInitialDelay.isNegative()
                ? DEFAULT_PRUNE_INITIAL_DELAY : pruneInitialDelay;
    }

    public static AiIndexProperties defaults() {
        return new AiIndexProperties(DEFAULT_WORKER_BASE_URL, DEFAULT_TIMEOUT_MS,
                DEFAULT_PRUNE_INTERVAL, DEFAULT_PRUNE_INITIAL_DELAY);
    }
}
