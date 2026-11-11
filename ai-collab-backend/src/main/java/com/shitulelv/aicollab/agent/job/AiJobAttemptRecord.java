package com.shitulelv.aicollab.agent.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code ai_job_attempt} 行（W5 冻结 §3.1 逐列对应）。 */
public record AiJobAttemptRecord(
        UUID id,
        UUID jobId,
        int attemptNo,
        AiJobAttemptStatus status,
        long leaseEpoch,
        OffsetDateTime startedAt,
        OffsetDateTime heartbeatAt,
        OffsetDateTime finishedAt,
        String errorCode,
        Integer inputTokens,
        Integer outputTokens) {
}
