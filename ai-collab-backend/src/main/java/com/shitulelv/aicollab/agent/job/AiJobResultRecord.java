package com.shitulelv.aicollab.agent.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code ai_job_result} 行（W5 冻结 §3.1 逐列对应）。
 *
 * <p>{@code reviewStatus} 本波只允许 {@code PENDING_REVIEW}：本记录不提供任何
 * ACCEPTED/REJECTED 的写路径，也没有“候选 → agent_memory”的转换。</p>
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
