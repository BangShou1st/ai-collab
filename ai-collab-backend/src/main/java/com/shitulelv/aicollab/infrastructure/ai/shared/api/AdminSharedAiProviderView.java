package com.shitulelv.aicollab.infrastructure.ai.shared.api;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfile;
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
        long capabilityConfigVersion,
        UUID modelProfileId,
        Integer contextWindowTokens,
        String contextWindowSource,
        Boolean contextWindowVerified) {

    public AdminSharedAiProviderView(
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
        this(id, name, providerType, baseUrl, apiPath, modelName, enabled, temperature, maxOutputTokens,
                capabilities, hasApiKey, isShared, sharedStatus, sharedPublishedAt, createdAt, updatedAt,
                capabilityConfigVersion, null, null, null, null);
    }

    public static AdminSharedAiProviderView from(UserAiProvider provider, SharedAiProvider shared) {
        return from(provider, shared, null);
    }

    public static AdminSharedAiProviderView from(
            UserAiProvider provider, SharedAiProvider shared, ModelProfile profile) {
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
                provider.capabilityConfigVersion(),
                profile != null ? profile.id() : null,
                profile != null ? profile.contextWindowTokens() : null,
                profile != null && profile.source() != null ? profile.source().name() : null,
                profile != null ? profile.verified() : null);
    }
}
