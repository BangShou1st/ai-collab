package com.shitulelv.aicollab.agent.application.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentSessionSummaryView(UUID id, UUID projectId, UUID creatorId, String creatorName,
        String title, String status, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        UUID latestRunId, String latestRunStatus, OffsetDateTime latestActivityAt,
        java.math.BigDecimal totalCost) {

    /** 兼容旧调用方的入口：不聚合成本（totalCost 为 null，表示无成本数据）。 */
    public AgentSessionSummaryView(UUID id, UUID projectId, UUID creatorId, String creatorName,
            String title, String status, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            UUID latestRunId, String latestRunStatus, OffsetDateTime latestActivityAt) {
        this(id, projectId, creatorId, creatorName, title, status, version, createdAt, updatedAt,
                latestRunId, latestRunStatus, latestActivityAt, null);
    }
}
