package com.shitulelv.aicollab.agent.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Memory 任务开关与边界（P1 契约 §4）。
 *
 * <p>{@code enabled=false}（默认）：管理员不能创建任务，补偿扫描与 Redisson 客户端都不装配。
 * {@code leaseSeconds} 是 claim/heartbeat 的续租时长，契约固定 60s。越界值按边界收敛，
 * 不因配置写错阻止启动。补偿扫描间隔由 {@code memory.job.compensation-interval}
 * （{@code AI_TASK_COMPENSATION_INTERVAL}，默认 30s）直接给 {@code @Scheduled} 使用。</p>
 */
@ConfigurationProperties("memory.job")
public record AiJobProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("60") int leaseSeconds) {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MIN_MAX_ATTEMPTS = 1;
    private static final int MAX_MAX_ATTEMPTS = 10;
    private static final int DEFAULT_LEASE_SECONDS = 60;
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
        return new AiJobProperties(false, DEFAULT_MAX_ATTEMPTS, DEFAULT_LEASE_SECONDS);
    }
}
