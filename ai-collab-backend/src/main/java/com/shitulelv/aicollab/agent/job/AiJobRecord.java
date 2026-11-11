package com.shitulelv.aicollab.agent.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code ai_job} 行（W5 冻结 §3.1 逐列对应）。
 *
 * <p>所有状态跃迁都带 {@code version} 做 CAS；{@code leaseEpoch} 单调递增，用于 fence
 * 迟到的心跳/结果。{@code payloadJson} 保持 JSONB 原文（不在此做业务解析）。</p>
 */
public record AiJobRecord(
        UUID id,
        UUID projectId,
        String jobType,
        AiJobStatus status,
        int schemaVersion,
        String payloadJson,
        int maxAttempts,
        int attemptCount,
        long leaseEpoch,
        UUID leaseToken,
        OffsetDateTime leaseExpiresAt,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime deadlineAt,
        String lastErrorCode,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {
}
