package com.shitulelv.aicollab.agent.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code ai_job} 行（W5 冻结 §3.1 逐列对应；V105 增加 {@code document_id / generation_id / parent_job_id / index_phase}）。
 *
 * <p>所有状态跃迁都带 {@code version} 做 CAS；{@code leaseEpoch} 单调递增，用于 fence
 * 迟到的心跳/结果。{@code payloadJson} 保持 JSONB 原文（不在此做业务解析）。</p>
 *
 * <p>{@code projectId} 只有 {@code REINDEX_GENERATION} 可为空（V105 {@code ck_ai_job_project_bound}，P2-R4 R4-1）；
 * 索引类任务是系统任务，{@code createdBy} 可为空；{@code indexPhase} 仅 {@code REINDEX_GENERATION}
 * 可非空（V105 {@code ck_ai_job_index_phase}，P2-R5 R5-2）。</p>
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
        int version,
        UUID documentId,
        UUID generationId,
        UUID parentJobId,
        String indexPhase) {

    /** P1 形状（无 V105 列），供 {@code MEMORY_EXTRACTION} 相关代码与测试沿用；新列取 {@code null}。 */
    public AiJobRecord(
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
        this(id, projectId, jobType, status, schemaVersion, payloadJson, maxAttempts, attemptCount, leaseEpoch,
                leaseToken, leaseExpiresAt, nextAttemptAt, deadlineAt, lastErrorCode, createdBy, createdAt,
                updatedAt, version, null, null, null, null);
    }

    /** V105 21 参旧形状（无 {@code indexPhase}），供已有调用方与测试沿用；{@code indexPhase} 取 {@code null}。 */
    public AiJobRecord(
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
            int version,
            UUID documentId,
            UUID generationId,
            UUID parentJobId) {
        this(id, projectId, jobType, status, schemaVersion, payloadJson, maxAttempts, attemptCount, leaseEpoch,
                leaseToken, leaseExpiresAt, nextAttemptAt, deadlineAt, lastErrorCode, createdBy, createdAt,
                updatedAt, version, documentId, generationId, parentJobId, null);
    }

    /** 三类索引任务（P2 契约 §4.2）。 */
    public boolean indexTask() {
        return AiJobTypes.INDEX_DOCUMENT.equals(jobType)
                || AiJobTypes.DELETE_DOCUMENT_INDEX.equals(jobType)
                || AiJobTypes.REINDEX_GENERATION.equals(jobType);
    }
}
