package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProtocol;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CapabilityProviderRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull ModelProviderType providerType,
        @NotNull CapabilityProtocol protocol,
        @NotBlank @Size(max = 2048) String baseUrl,
        @NotBlank @Size(max = 512) String apiPath,
        @NotBlank @Pattern(regexp = "NONE|API_KEY|BEARER|CUSTOM_HEADER") String authType,
        @NotBlank @Pattern(regexp = "local|internal|public") String networkZone,
        @Size(max = 512) String healthPath,
        @Size(max = 1000) String apiKey,
        boolean enabled,
        @Size(max = 500) String reason) {
}
