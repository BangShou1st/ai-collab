package com.shitulelv.aicollab.project.application.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DashboardRecentDocumentView(
        UUID id,
        String originalFilename,
        String status,
        UUID uploadedBy,
        String uploaderDisplayName,
        OffsetDateTime createdAt) {
}
