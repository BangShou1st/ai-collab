package com.shitulelv.aicollab.agent.application.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentMemoryView(
        UUID id, UUID projectId, String type, String title, String content,
        String sourceType, UUID sourceId, String status, UUID createdBy,
        UUID updatedBy, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
