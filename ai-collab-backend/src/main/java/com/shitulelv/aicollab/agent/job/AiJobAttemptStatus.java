package com.shitulelv.aicollab.agent.job;

import java.util.Locale;

/** 单次尝试状态（W5 冻结 §3.1 {@code ck_ai_job_attempt_status} 逐值一致）。 */
public enum AiJobAttemptStatus {

    RUNNING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED,
    EXPIRED;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
