package com.shitulelv.aicollab.agent.job;

import java.util.Locale;

/**
 * AI Job 状态（W5 冻结 §3.2，与 {@code ck_ai_job_status} 逐值一致）。
 *
 * <pre>
 * QUEUED --lease--> LEASED --start/heartbeat--> RUNNING --success--> SUCCEEDED
 *                                                    \--retryable--> FAILED_RETRYABLE --retry--> QUEUED
 *                                                    \--fatal-----> FAILED
 * QUEUED/LEASED/RUNNING --deadline--> EXPIRED
 * 任意非终态 --cancel--> CANCELED
 * </pre>
 */
public enum AiJobStatus {

    QUEUED,
    LEASED,
    RUNNING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED,
    CANCELED,
    EXPIRED;

    /** 终态：不再接受心跳/结果/重试。 */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED || this == EXPIRED;
    }

    /** 是否处于“租约持有中”（LEASED/RUNNING）。 */
    public boolean leased() {
        return this == LEASED || this == RUNNING;
    }

    /** 指标标签用小写取值（低基数、稳定）。 */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 数据库读取：未知值直接失败，避免脏状态静默降级。 */
    public static AiJobStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("ai_job.status 不能为空");
        }
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
