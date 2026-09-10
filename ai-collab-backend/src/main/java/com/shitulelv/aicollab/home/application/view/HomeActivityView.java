package com.shitulelv.aicollab.home.application.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record HomeActivityView(UUID id, UUID projectId, String projectName, String action,
        OffsetDateTime createdAt) {
}
