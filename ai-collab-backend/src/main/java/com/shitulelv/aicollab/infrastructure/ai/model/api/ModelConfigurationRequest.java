package com.shitulelv.aicollab.infrastructure.ai.model.api;

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

public record ModelConfigurationRequest(
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
}
