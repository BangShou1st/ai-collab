package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProtocol;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CapabilityProfileRequest(
        @NotNull UUID modelProfileId,
        @NotNull UUID providerId,
        @NotNull CapabilityType capability,
        @NotNull CapabilityProtocol protocol,
        @NotNull JsonNode settings,
        @Pattern(regexp = "UNVERIFIED|PROVIDER_DOCUMENT|ADMIN_VERIFIED|LOCAL_FILE") String source,
        @Size(max = 500) String reason) {
}
