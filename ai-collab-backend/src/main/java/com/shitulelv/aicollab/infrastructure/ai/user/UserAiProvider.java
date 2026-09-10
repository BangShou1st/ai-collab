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
        String presetCode) {
    public UserAiProvider {
        if (presetCode != null && presetCode.isBlank()) presetCode = null;
    }
    public boolean isPreset() { return presetCode != null; }

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
