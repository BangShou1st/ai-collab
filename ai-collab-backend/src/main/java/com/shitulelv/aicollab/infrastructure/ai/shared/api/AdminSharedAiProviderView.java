package com.shitulelv.aicollab.infrastructure.ai.shared.api;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;

public record AdminSharedAiProviderView(
        UUID id,
        String name,
        ModelProviderType providerType,
        String baseUrl,
        String apiPath,
        String modelName,
        boolean enabled,
        double temperature,
        int maxOutputTokens,
        EnumSet<ModelCapability> capabilities,
        boolean hasApiKey,
        boolean isShared,
        String sharedStatus,
        OffsetDateTime sharedPublishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long capabilityConfigVersion) {

    public static AdminSharedAiProviderView from(UserAiProvider provider, SharedAiProvider shared) {
        boolean sharedActive = shared != null && shared.isPublished();
        return new AdminSharedAiProviderView(
                provider.id(),
                provider.name(),
                provider.providerType(),
                provider.baseUrl(),
                provider.apiPath(),
                provider.modelName(),
                provider.enabled(),
                provider.temperature(),
                provider.maxOutputTokens(),
                provider.capabilities(),
                provider.encryptedApiKey() != null && !provider.encryptedApiKey().isBlank(),
                sharedActive,
                shared != null ? shared.status() : "UNPUBLISHED",
                shared != null ? shared.publishedAt() : null,
                provider.createdAt(),
                provider.updatedAt(),
                provider.capabilityConfigVersion());
    }
}
