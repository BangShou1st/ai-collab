package com.shitulelv.aicollab.project.application.view;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Dashboard 最近动态视图。脱敏，不暴露 requestId 和完整 detail。
 */
public record DashboardActivityView(
        UUID id,
        String userDisplayName,
        String action,
        String entityType,
        UUID entityId,
        String summary,
        OffsetDateTime createdAt) {
}
