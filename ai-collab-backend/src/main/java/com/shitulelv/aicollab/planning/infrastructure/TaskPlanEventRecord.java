package com.shitulelv.aicollab.planning.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Task 7: Immutable record for plan audit events.
 */
public record TaskPlanEventRecord(
        UUID id,
        UUID planId,
        UUID fromVersionId,
        UUID toVersionId,
        UUID actorId,
        String eventType,
        List<String> changedFields,
        String beforeHash,
        String afterHash,
        List<String> issueCodes,
        OffsetDateTime createdAt
) {
    public TaskPlanEventRecord {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }
}
