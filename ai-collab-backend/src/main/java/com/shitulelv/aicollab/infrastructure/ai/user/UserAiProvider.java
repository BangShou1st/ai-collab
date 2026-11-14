package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;

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
        OffsetDateTime updatedAt,
        boolean shared,
        long capabilityConfigVersion) {
    /**
     * 兼容旧调用方的便捷构造：能力配置版本以 0 起算。
     * 仅用于新建行与重建展示快照；更新路径的版本推进由仓储条件更新决定。
     */
    public UserAiProvider(UUID id, UUID userId, String name, ModelProviderType providerType,
            String baseUrl, String apiPath, String encryptedApiKey, String modelName,
            boolean enabled, double temperature, int maxOutputTokens,
            EnumSet<ModelCapability> capabilities, boolean isDefault,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, boolean shared) {
        this(id, userId, name, providerType, baseUrl, apiPath, encryptedApiKey, modelName,
                enabled, temperature, maxOutputTokens, capabilities, isDefault,
                createdAt, updatedAt, shared, 0);
    }

    /**
     * 映射为 Provider Adapter 可用的配置视图。
     * projectId 槽位承载 Credential 归属者 userId，仅供 adapter 上下文使用，不代表项目归属。
     */
    public ModelConfiguration toModelConfiguration() {
        return new ModelConfiguration(
                id, userId, name, providerType, baseUrl, apiPath, encryptedApiKey, modelName,
                enabled, temperature, maxOutputTokens, capabilities, createdAt, updatedAt);
    }
}
