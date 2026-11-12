package com.shitulelv.aicollab.infrastructure.ai.capability.api;

import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityAssignment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CapabilityAssignmentView(
        UUID id,
        String capability,
        String purpose,
        String scope,
        UUID projectId,
        UUID profileId,
        String profileStatus,
        int profileVersion,
        boolean enabled,
        OffsetDateTime updatedAt) {

    public static CapabilityAssignmentView from(CapabilityAssignment assignment,
            String profileStatus, int profileVersion) {
        return new CapabilityAssignmentView(
                assignment.id(), assignment.capability().name(), assignment.purpose(),
                assignment.scope().name(), assignment.projectId(), assignment.modelCapabilityProfileId(),
                profileStatus, profileVersion, assignment.enabled(), assignment.updatedAt());
    }
}
