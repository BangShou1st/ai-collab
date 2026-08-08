package com.shitulelv.aicollab.infrastructure.ai.embedding;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectEmbeddingConfig(
        UUID projectId,
        String provider,
        String baseUrl,
        String apiPath,
        String encryptedApiKey,
        String modelName,
        int dimensions,
        int batchSize,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
