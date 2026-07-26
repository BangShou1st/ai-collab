package com.shitulelv.aicollab.document.application.view;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentView(
        UUID id,
        UUID projectId,
        String displayName,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        DocumentStatus status,
        String parserType,
        int chunkCount,
        String embeddingProvider,
        String embeddingModel,
        Integer embeddingDimension,
        String errorMessage,
        UUID uploadedById,
        String uploadedByDisplayName,
        OffsetDateTime indexedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
