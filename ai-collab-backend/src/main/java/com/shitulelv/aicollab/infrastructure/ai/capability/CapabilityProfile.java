package com.shitulelv.aicollab.infrastructure.ai.capability;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CapabilityProfile(
        UUID id,
        UUID modelProfileId,
        UUID providerId,
        CapabilityType capability,
        String protocol,
        String settingsJson,
        CapabilityProfileStatus status,
        int version,
        String source,
        OffsetDateTime verifiedAt,
        UUID verifiedBy,
        UUID createdBy,
        UUID updatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public boolean isPublished() {
        return status == CapabilityProfileStatus.PUBLISHED;
    }
}
