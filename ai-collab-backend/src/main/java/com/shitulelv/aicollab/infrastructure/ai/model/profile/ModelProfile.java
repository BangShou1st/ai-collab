package com.shitulelv.aicollab.infrastructure.ai.model.profile;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一个实际出站模型（Provider + 端点 + 模型名）的能力档案。
 *
 * <p>{@code contextWindowTokens} 是经核实或声明待核实的上下文容量；是否可信由
 * {@link ModelProfileSource} 决定，调用方不得只看数值。</p>
 */
public record ModelProfile(
        UUID id,
        String providerType,
        String baseUrl,
        String apiPath,
        String modelName,
        int contextWindowTokens,
        Integer maxOutputTokens,
        boolean supportsUsage,
        String tokenizerKind,
        String pricingVersion,
        ModelProfileSource source,
        OffsetDateTime verifiedAt,
        UUID verifiedBy,
        OffsetDateTime updatedAt,
        String modelKey,
        String displayName,
        String modelVersion,
        String sha256,
        String license) {

    public ModelProfile {
        modelKey = modelKey == null || modelKey.isBlank() ? modelName : modelKey.strip();
        displayName = displayName == null || displayName.isBlank() ? modelName : displayName.strip();
    }

    /** 兼容 V89 既有构造调用；新增元数据缺省为模型名/空值。 */
    public ModelProfile(
            UUID id,
            String providerType,
            String baseUrl,
            String apiPath,
            String modelName,
            int contextWindowTokens,
            Integer maxOutputTokens,
            boolean supportsUsage,
            String tokenizerKind,
            String pricingVersion,
            ModelProfileSource source,
            OffsetDateTime verifiedAt,
            UUID verifiedBy,
            OffsetDateTime updatedAt) {
        this(id, providerType, baseUrl, apiPath, modelName, contextWindowTokens, maxOutputTokens,
                supportsUsage, tokenizerKind, pricingVersion, source, verifiedAt, verifiedBy,
                updatedAt, modelName, modelName, null, null, null);
    }

    public boolean verified() {
        return source != null && source.verified();
    }
}
