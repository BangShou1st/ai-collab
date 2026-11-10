package com.shitulelv.aicollab.infrastructure.ai.user.api;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UserAiProviderRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull ModelProviderType providerType,
        @NotBlank @Size(max = 2048) String baseUrl,
        @NotBlank @Size(max = 512) String apiPath,
        @Size(max = 1000) String apiKey,
        @NotBlank @Size(max = 160) String modelName,
        boolean enabled,
        @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
        @Min(1) @Max(131072) int maxOutputTokens,
        @NotEmpty Set<ModelCapability> capabilities,
        // 成本台账（设计文档 §4.4）：每 1000 token 单价，用户自填；null = 不计成本（整体替换语义）
        @jakarta.validation.constraints.Digits(integer = 4, fraction = 6) @DecimalMin("0.0") java.math.BigDecimal pricePer1kInput,
        @jakarta.validation.constraints.Digits(integer = 4, fraction = 6) @DecimalMin("0.0") java.math.BigDecimal pricePer1kOutput) {

    /** 兼容旧调用方的入口：不填价格（按 null 处理，即不计成本）。 */
    public UserAiProviderRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull ModelProviderType providerType,
            @NotBlank @Size(max = 2048) String baseUrl,
            @NotBlank @Size(max = 512) String apiPath,
            @Size(max = 1000) String apiKey,
            @NotBlank @Size(max = 160) String modelName,
            boolean enabled,
            @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
            @Min(1) @Max(131072) int maxOutputTokens,
            @NotEmpty Set<ModelCapability> capabilities) {
        this(name, providerType, baseUrl, apiPath, apiKey, modelName, enabled, temperature,
                maxOutputTokens, capabilities, null, null);
    }
}
