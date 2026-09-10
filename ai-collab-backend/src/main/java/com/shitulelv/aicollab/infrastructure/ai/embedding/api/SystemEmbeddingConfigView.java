package com.shitulelv.aicollab.infrastructure.ai.embedding.api;

import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfig;

public record SystemEmbeddingConfigView(
        String provider,
        String baseUrl,
        String apiPath,
        boolean hasApiKey,
        String modelName,
        int dimensions,
        int batchSize,
        boolean enabled,
        String fingerprint,
        long staleChunks) {

    public static SystemEmbeddingConfigView from(SystemEmbeddingConfig config, long staleChunks) {
        return new SystemEmbeddingConfigView(
                config.provider(), config.baseUrl(), config.apiPath(),
                config.encryptedApiKey() != null && !config.encryptedApiKey().isBlank(),
                config.modelName(), config.dimensions(), config.batchSize(), config.enabled(),
                config.fingerprint(), staleChunks);
    }

    public static SystemEmbeddingConfigView empty() {
        return new SystemEmbeddingConfigView(null, null, null, false, null, 0, 0, false, null, 0);
    }

    public String model() {
        return modelName();
    }
}
