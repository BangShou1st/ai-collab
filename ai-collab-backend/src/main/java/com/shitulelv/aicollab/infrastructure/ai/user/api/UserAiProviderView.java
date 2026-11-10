package com.shitulelv.aicollab.infrastructure.ai.user.api;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;

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
        OffsetDateTime updatedAt,
        java.math.BigDecimal pricePer1kInput,
        java.math.BigDecimal pricePer1kOutput) {

    /** 兼容旧调用方的入口：不携带价格（null = 未返回价格数据）。 */
    public UserAiProviderView(
            UUID id, String name, ModelProviderType providerType, String baseUrl, String apiPath,
            boolean hasApiKey, String modelName, boolean enabled, boolean isDefault, String presetCode,
            double temperature, int maxOutputTokens, Set<ModelCapability> capabilities,
            OffsetDateTime updatedAt) {
        this(id, name, providerType, baseUrl, apiPath, hasApiKey, modelName, enabled, isDefault,
                presetCode, temperature, maxOutputTokens, capabilities, updatedAt, null, null);
    }

    public static UserAiProviderView from(UserAiProvider value) {
        return from(value, null);
    }

    /**
     * 视图组装：价格不在 {@link UserAiProvider} 聚合内（价格不影响真实请求行为），
     * 由仓储单独读取后传入；null 表示该配置未填价格（不计成本）。
     */
    public static UserAiProviderView from(UserAiProvider value, UserAiProviderRepository.ProviderPrices prices) {
        return new UserAiProviderView(
                value.id(), value.name(), value.providerType(), value.baseUrl(), value.apiPath(),
                value.encryptedApiKey() != null && !value.encryptedApiKey().isBlank(),
                value.modelName(), value.enabled(), value.isDefault(), value.presetCode(), value.temperature(),
                value.maxOutputTokens(), Set.copyOf(value.capabilities()), value.updatedAt(),
                prices == null ? null : prices.pricePer1kInput(),
                prices == null ? null : prices.pricePer1kOutput());
    }
}
