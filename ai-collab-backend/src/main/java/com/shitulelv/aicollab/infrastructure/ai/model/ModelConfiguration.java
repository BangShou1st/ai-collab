package com.shitulelv.aicollab.infrastructure.ai.model;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;

public record ModelConfiguration(
        UUID id,
        String name,
        ModelProviderType providerType,
        String baseUrl,
        String apiPath,
        String encryptedApiKey,
        String modelName,
        boolean enabled,
        double temperature,
        int maxOutputTokens,
        EnumSet<ModelCapability> capabilities,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public ModelConfiguration {
        capabilities = capabilities == null
                ? EnumSet.noneOf(ModelCapability.class)
                : EnumSet.copyOf(capabilities);
    }
}
