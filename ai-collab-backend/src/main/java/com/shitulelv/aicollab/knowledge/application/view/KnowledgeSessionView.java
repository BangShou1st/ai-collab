package com.shitulelv.aicollab.knowledge.application.view;

import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeSessionView(
        UUID id,
        UUID projectId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static KnowledgeSessionView from(KnowledgeSessionEntity entity) {
        return new KnowledgeSessionView(
                entity.getId(), entity.getProjectId(), entity.getTitle(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
