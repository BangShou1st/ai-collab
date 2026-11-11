package com.shitulelv.aicollab.internal.ai.job;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 内部 Job 端点的请求/响应 DTO（W5 冻结 §3.3 逐字段对应；不包 ApiResponse）。 */
public final class InternalAiJobDtos {

    private InternalAiJobDtos() {
    }

    /** GET snapshot 响应；总量 <64 KiB。 */
    public record SnapshotResponse(
            int schemaVersion,
            UUID jobId,
            UUID projectId,
            int attempt,
            long leaseEpoch,
            String snapshotHash,
            List<Fact> facts,
            List<ExistingMemory> existingMemories) {

        public SnapshotResponse {
            facts = facts == null ? List.of() : List.copyOf(facts);
            existingMemories = existingMemories == null ? List.of() : List.copyOf(existingMemories);
        }
    }

    public record Fact(String ref, String kind, String text) {
    }

    public record ExistingMemory(UUID id, String type, String title, String content) {
    }

    /** POST heartbeat 请求。 */
    public record HeartbeatRequest(Integer schemaVersion, Integer attempt, Long leaseEpoch) {
    }

    /** POST heartbeat 响应；leaseExpiresAt 用 ISO-8601 字符串，避免宿主时间模块差异。 */
    public record HeartbeatResponse(int schemaVersion, String status, String leaseExpiresAt) {
    }

    /** POST results 请求。 */
    public record ResultsRequest(
            Integer schemaVersion,
            Integer attempt,
            Long leaseEpoch,
            List<Candidate> candidates) {
    }

    /** Memory 候选（§3.5）：本波只落 ai_job_result，review_status 恒 PENDING_REVIEW。 */
    public record Candidate(
            String kind,
            String title,
            String content,
            BigDecimal confidence,
            String sourceRef,
            String dedupeKey) {
    }

    /** POST results 响应；status 固定 SUCCEEDED。 */
    public record ResultsResponse(
            int schemaVersion,
            UUID jobId,
            String status,
            int accepted,
            int duplicates) {
    }
}
