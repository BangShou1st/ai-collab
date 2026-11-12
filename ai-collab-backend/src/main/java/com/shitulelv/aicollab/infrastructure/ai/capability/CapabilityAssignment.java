package com.shitulelv.aicollab.infrastructure.ai.capability;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CapabilityAssignment(
        UUID id,
        CapabilityType capability,
        String purpose,
        CapabilityAssignmentScope scope,
        UUID projectId,
        UUID modelCapabilityProfileId,
        boolean enabled,
        UUID createdBy,
        UUID updatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
