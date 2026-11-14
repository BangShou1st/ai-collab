package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProvider;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 管理员视图：不包含密文、明文密钥或模型文件路径。 */
public record CapabilityProviderView(
        UUID id,
        String name,
        String providerType,
        String protocol,
        String baseUrl,
        String apiPath,
        String authType,
        String networkZone,
        String healthPath,
        boolean enabled,
        String status,
        boolean hasApiKey,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static CapabilityProviderView from(CapabilityProvider provider) {
        return new CapabilityProviderView(
                provider.id(),
                provider.name(),
                provider.providerType().name(),
                provider.protocol(),
                provider.baseUrl(),
                provider.apiPath(),
                provider.authType(),
                provider.networkZone(),
                provider.healthPath(),
                provider.enabled(),
                provider.status(),
                provider.encryptedApiKey() != null && !provider.encryptedApiKey().isBlank(),
                provider.createdAt(),
                provider.updatedAt());
    }
}
