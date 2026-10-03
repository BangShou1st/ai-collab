package com.shitulelv.aicollab.infrastructure.ai.embedding;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SystemEmbeddingConfig(
        UUID id,
        String provider,
        String baseUrl,
        String apiPath,
        String encryptedApiKey,
        String modelName,
        int dimensions,
        int batchSize,
        String fingerprint,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID generationId) {
    public SystemEmbeddingConfig(UUID id, String provider, String baseUrl, String apiPath, String encryptedApiKey,
            String modelName, int dimensions, int batchSize, String fingerprint, boolean enabled,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, provider, baseUrl, apiPath, encryptedApiKey, modelName, dimensions, batchSize,
                fingerprint, enabled, createdAt, updatedAt, null);
    }
}
