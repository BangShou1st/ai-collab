package com.shitulelv.aicollab.infrastructure.ai.model.profile.api;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileSource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理员维护模型窗口档案的入参。
 *
 * <p>{@code providerType + baseUrl + apiPath + modelName} 共同定位一个实际出站模型；
 * {@code contextWindowTokens} 为经核实或待核实的上下文容量。</p>
 */
public record ModelProfileRequest(
        @NotNull ModelProviderType providerType,
        @NotBlank @Size(max = 2048) String baseUrl,
        @NotBlank @Size(max = 512) String apiPath,
        @NotBlank @Size(max = 160) String modelName,
        @Min(1) int contextWindowTokens,
        @Min(1) Integer maxOutputTokens,
        boolean supportsUsage,
        @Size(max = 32) String tokenizerKind,
        @Size(max = 64) String pricingVersion,
        @NotNull ModelProfileSource source,
        @Size(max = 160) String modelKey,
        @Size(max = 160) String displayName,
        @Size(max = 80) String modelVersion,
        @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "SHA-256 必须是 64 位十六进制") String sha256,
        @Size(max = 160) String license) {

    /** 兼容既有调用方：未提供扩展模型身份时由 modelName 派生。 */
    public ModelProfileRequest(
            ModelProviderType providerType,
            String baseUrl,
            String apiPath,
            String modelName,
            int contextWindowTokens,
            Integer maxOutputTokens,
            boolean supportsUsage,
            String tokenizerKind,
            String pricingVersion,
            ModelProfileSource source) {
        this(providerType, baseUrl, apiPath, modelName, contextWindowTokens, maxOutputTokens,
                supportsUsage, tokenizerKind, pricingVersion, source, null, null, null, null, null);
    }
}
