package com.shitulelv.aicollab.agent.application.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentSessionView(
        UUID id,
        UUID projectId,
        UUID creatorId,
        String title,
        String status,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
