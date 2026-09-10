package com.shitulelv.aicollab.infrastructure.ai.user.api;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record UserAiProviderView(
        UUID id,
        String name,
        ModelProviderType providerType,
        String baseUrl,
        String apiPath,
        boolean hasApiKey,
        String modelName,
        boolean enabled,
        boolean isDefault,
        String presetCode,
        double temperature,
        int maxOutputTokens,
        Set<ModelCapability> capabilities,
        OffsetDateTime updatedAt) {

    public static UserAiProviderView from(UserAiProvider value) {
        return new UserAiProviderView(
                value.id(), value.name(), value.providerType(), value.baseUrl(), value.apiPath(),
                value.encryptedApiKey() != null && !value.encryptedApiKey().isBlank(),
                value.modelName(), value.enabled(), value.isDefault(), value.presetCode(), value.temperature(),
                value.maxOutputTokens(), Set.copyOf(value.capabilities()), value.updatedAt());
    }
}
