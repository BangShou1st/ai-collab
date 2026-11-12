package com.shitulelv.aicollab.agent.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code ai_job_result} 行（W5 冻结 §3.1 逐列对应）。
 *
 * <p>{@code reviewStatus} 初始为 {@code PENDING_REVIEW}；管理员审核服务负责
 * PENDING_REVIEW → ACCEPTED/REJECTED 的 CAS 与发布，不在本 record 内隐式执行。</p>
 */
public record AiJobResultRecord(
        UUID id,
        UUID jobId,
        int attemptNo,
        String resultKind,
        String dedupeKey,
        String payloadJson,
        String reviewStatus,
        OffsetDateTime createdAt) {
}
