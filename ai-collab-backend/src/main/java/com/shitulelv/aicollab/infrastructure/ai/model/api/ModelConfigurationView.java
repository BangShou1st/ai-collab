package com.shitulelv.aicollab.infrastructure.ai.model.api;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record ModelConfigurationView(
        UUID id,
        String name,
        ModelProviderType providerType,
        String baseUrl,
        String apiPath,
        boolean hasApiKey,
        String modelName,
        boolean enabled,
        double temperature,
        int maxOutputTokens,
        Set<ModelCapability> capabilities,
        OffsetDateTime updatedAt) {

    public static ModelConfigurationView from(ModelConfiguration value) {
        return new ModelConfigurationView(
                value.id(), value.name(), value.providerType(), value.baseUrl(), value.apiPath(),
                value.encryptedApiKey() != null && !value.encryptedApiKey().isBlank(),
                value.modelName(), value.enabled(), value.temperature(), value.maxOutputTokens(),
                Set.copyOf(value.capabilities()), value.updatedAt());
    }
}
