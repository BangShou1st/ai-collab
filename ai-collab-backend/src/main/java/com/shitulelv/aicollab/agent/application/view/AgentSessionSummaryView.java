package com.shitulelv.aicollab.agent.application.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentSessionSummaryView(UUID id, UUID projectId, UUID creatorId, String creatorName,
        String title, String status, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        UUID latestRunId, String latestRunStatus, OffsetDateTime latestActivityAt) {}
