package com.shitulelv.aicollab.document.infrastructure.repository;

import com.shitulelv.aicollab.document.domain.model.DocumentDeletionJobStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentDeletionJob(
        UUID id,
        UUID projectId,
        UUID documentId,
        String objectKey,
        DocumentDeletionJobStatus status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime leaseUntil,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
