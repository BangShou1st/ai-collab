package com.shitulelv.aicollab.notification.infrastructure.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminNotification(
        UUID id,
        String category,
        String level,
        String title,
        String content,
        String targetPath,
        String dedupeKey,
        boolean isRead,
        OffsetDateTime readAt,
        OffsetDateTime createdAt) {

    public static AdminNotification fresh(
            String category,
            String level,
            String title,
            String content,
            String targetPath,
            String dedupeKey) {
        return new AdminNotification(
                UUID.randomUUID(),
                category,
                level,
                title,
                content,
                targetPath,
                dedupeKey,
                false,
                null,
                OffsetDateTime.now());
    }
}
