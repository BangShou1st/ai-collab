package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProfile;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CapabilityProfileView(
        UUID id,
        UUID modelProfileId,
        String modelKey,
        String modelDisplayName,
        UUID providerId,
        String providerName,
        String capability,
        String protocol,
        JsonNode settings,
        String status,
        int version,
        String source,
        OffsetDateTime verifiedAt,
        UUID verifiedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static CapabilityProfileView from(CapabilityProfile profile, String modelKey,
            String modelDisplayName, String providerName, JsonNode settings) {
        return new CapabilityProfileView(
                profile.id(), profile.modelProfileId(), modelKey, modelDisplayName,
                profile.providerId(), providerName, profile.capability().name(), profile.protocol(),
                settings, profile.status().name(), profile.version(), profile.source(),
                profile.verifiedAt(), profile.verifiedBy(), profile.createdAt(), profile.updatedAt());
    }
}
