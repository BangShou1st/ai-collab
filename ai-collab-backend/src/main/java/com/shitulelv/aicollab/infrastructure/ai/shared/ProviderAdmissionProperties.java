package com.shitulelv.aicollab.infrastructure.ai.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 管理员 Provider 发布前的最小延迟准入阈值。
 *
 * <p>默认值刻意保守：只拦截“明显超时/不稳定”的配置，不宣称任何生产容量。
 * 样本数少于 2 时无法证明稳定性，因此收敛到默认 3；所有阈值都可通过
 * {@code AI_PROVIDER_ADMISSION_*} 环境变量覆盖。</p>
 */
@ConfigurationProperties("ai.provider-admission")
public record ProviderAdmissionProperties(
        @DefaultValue("3") int samples,
        @DefaultValue("10000") long p95MaxMs,
        @DefaultValue("0") int maxTimeouts,
        @DefaultValue("0") int maxFailures) {

    private static final int DEFAULT_SAMPLES = 3;
    private static final long DEFAULT_P95_MAX_MS = 10_000L;

    public ProviderAdmissionProperties {
        if (samples < 2 || samples > 10) samples = DEFAULT_SAMPLES;
        if (p95MaxMs < 1) p95MaxMs = DEFAULT_P95_MAX_MS;
        if (maxTimeouts < 0) maxTimeouts = 0;
        if (maxFailures < 0) maxFailures = 0;
    }

    public static ProviderAdmissionProperties defaults() {
        return new ProviderAdmissionProperties(
                DEFAULT_SAMPLES, DEFAULT_P95_MAX_MS, 0, 0);
    }
}
