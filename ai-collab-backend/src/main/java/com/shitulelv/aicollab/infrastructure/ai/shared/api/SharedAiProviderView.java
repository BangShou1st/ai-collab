package com.shitulelv.aicollab.infrastructure.ai.shared.api;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;

/**
 * 普通用户可见的全站共享 AI 供应商视图。
 * 绝不暴露 API Key、加密串或敏感后端端点。
 */
public record SharedAiProviderView(
        UUID id,
        String name,
        ModelProviderType providerType,
        String modelName,
        boolean enabled,
        String status,
        EnumSet<ModelCapability> capabilities,
        double temperature,
        int maxOutputTokens,
        OffsetDateTime publishedAt) {

    public static SharedAiProviderView from(SharedAiProvider provider) {
        return new SharedAiProviderView(
                provider.id(),
                provider.name(),
                provider.providerType(),
                provider.modelName(),
                provider.enabled(),
                provider.status(),
                provider.capabilities(),
                provider.temperature(),
                provider.maxOutputTokens(),
                provider.publishedAt());
    }
}
