package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;

public record UserAiProvider(
        UUID id,
        UUID userId,
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
        boolean isDefault,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
