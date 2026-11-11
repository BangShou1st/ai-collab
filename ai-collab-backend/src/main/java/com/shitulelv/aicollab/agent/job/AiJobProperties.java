package com.shitulelv.aicollab.agent.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Memory Job 开关与边界（W5 冻结 §5）。
 *
 * <p><b>默认必须“不切生产”</b>：{@code enabled=false}，没有 dispatcher 进入生产路径，
 * 也没有自动调度；{@code recoveryEnabled=false} 时恢复任务不装配。越界值按边界收敛，
 * 不因配置写错阻止启动（唯一开关本身就是 enabled）。</p>
 */
@ConfigurationProperties("memory.job")
public record AiJobProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("120") int leaseSeconds,
        @DefaultValue("false") boolean recoveryEnabled) {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MIN_MAX_ATTEMPTS = 1;
    private static final int MAX_MAX_ATTEMPTS = 10;
    private static final int DEFAULT_LEASE_SECONDS = 120;
    private static final int MIN_LEASE_SECONDS = 10;
    private static final int MAX_LEASE_SECONDS = 900;

    public AiJobProperties {
        if (maxAttempts == Integer.MIN_VALUE || maxAttempts == Integer.MAX_VALUE) {
            maxAttempts = DEFAULT_MAX_ATTEMPTS;
        }
        maxAttempts = Math.max(MIN_MAX_ATTEMPTS, Math.min(maxAttempts, MAX_MAX_ATTEMPTS));
        if (leaseSeconds == Integer.MIN_VALUE || leaseSeconds == Integer.MAX_VALUE) {
            leaseSeconds = DEFAULT_LEASE_SECONDS;
        }
        leaseSeconds = Math.max(MIN_LEASE_SECONDS, Math.min(leaseSeconds, MAX_LEASE_SECONDS));
    }

    /** 默认值，供不依赖 Spring Binder 的代码与测试使用。 */
    public static AiJobProperties defaults() {
        return new AiJobProperties(false, DEFAULT_MAX_ATTEMPTS, DEFAULT_LEASE_SECONDS, false);
    }
}
