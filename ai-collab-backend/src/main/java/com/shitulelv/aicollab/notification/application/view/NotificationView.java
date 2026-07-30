package com.shitulelv.aicollab.notification.application.view;

import com.shitulelv.aicollab.notification.infrastructure.entity.NotificationEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationView(
        UUID id,
        UUID projectId,
        String projectName,
        String type,
        String title,
        String content,
        String entityType,
        UUID entityId,
        boolean read,
        OffsetDateTime createdAt
) {
    public static NotificationView from(NotificationEntity entity, String projectName) {
        return new NotificationView(
                entity.getId(),
                entity.getProjectId(),
                projectName == null ? "" : projectName,
                entity.getType(),
                entity.getTitle(),
                entity.getContent(),
                entity.getEntityType(),
                entity.getEntityId(),
                entity.isRead(),
                entity.getCreatedAt()
        );
    }
}
