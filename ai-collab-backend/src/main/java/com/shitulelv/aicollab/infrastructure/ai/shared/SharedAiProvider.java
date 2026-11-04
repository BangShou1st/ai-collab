package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;

/**
 * 全站共享 AI 供应商实体。
 * 由系统管理员发布，供全平台用户选用。
 */
public record SharedAiProvider(
        UUID id,
        UUID sourceProviderId,
        UUID publisherUserId,
        String name,
        ModelProviderType providerType,
        String baseUrl,
        String apiPath,
        String encryptedApiKey,
        String modelName,
        boolean enabled,
        String status,
        double temperature,
        int maxOutputTokens,
        EnumSet<ModelCapability> capabilities,
        long capabilityConfigVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime publishedAt) {

    public static final String PRESET_CODE_SHARED = "SHARED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_REVOKED = "REVOKED";

    public boolean isPublished() {
        return STATUS_PUBLISHED.equalsIgnoreCase(status) && enabled;
    }

    /**
     * 转换为供 Chat/Planning/Agent 网关调用的运行时模型配置。
     * presetCode 标记为 "SHARED"，解密凭据时使用发布者密文。
     */
    public UserAiProvider toRuntimeUserAiProvider() {
        return new UserAiProvider(
                id,
                publisherUserId,
                name,
                providerType,
                baseUrl,
                apiPath,
                encryptedApiKey,
                modelName,
                isPublished(),
                temperature,
                maxOutputTokens,
                capabilities,
                false,
                createdAt,
                updatedAt,
                PRESET_CODE_SHARED,
                capabilityConfigVersion);
    }

    public ModelConfiguration toModelConfiguration() {
        return new ModelConfiguration(
                id,
                publisherUserId,
                name,
                providerType,
                baseUrl,
                apiPath,
                encryptedApiKey,
                modelName,
                isPublished(),
                temperature,
                maxOutputTokens,
                capabilities,
                createdAt,
                updatedAt);
    }
}
