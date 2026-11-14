package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 管理端共享 Provider 的能力视图；密文只用于服务端出站，不进入任何响应。 */
public record CapabilityProvider(
        UUID id,
        String name,
        ModelProviderType providerType,
        String protocol,
        String baseUrl,
        String apiPath,
        String authType,
        String networkZone,
        String healthPath,
        String encryptedApiKey,
        boolean enabled,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime publishedAt) {

    public boolean isAvailable() {
        return enabled && "PUBLISHED".equalsIgnoreCase(status);
    }

    public boolean requiresAuth() {
        return authType != null && !"NONE".equalsIgnoreCase(authType);
    }
}
