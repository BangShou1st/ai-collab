package com.shitulelv.aicollab.agent.application.view;

import com.shitulelv.aicollab.agent.job.AiJobRecord;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 管理 API 的 AI Job 安全视图：不暴露 payload 正文。 */
public record AiJobView(
        UUID id,
        UUID projectId,
        String jobType,
        String status,
        int attemptCount,
        int maxAttempts,
        long leaseEpoch,
        OffsetDateTime leaseExpiresAt,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime deadlineAt,
        String lastErrorCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static AiJobView from(AiJobRecord job) {
        return new AiJobView(job.id(), job.projectId(), job.jobType(), job.status().name(),
                job.attemptCount(), job.maxAttempts(), job.leaseEpoch(), job.leaseExpiresAt(),
                job.nextAttemptAt(), job.deadlineAt(), job.lastErrorCode(), job.createdAt(),
                job.updatedAt());
    }
}
