package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRecord;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Public audit view. Internal actor identifiers and integrity hashes never cross the API boundary. */
public record TaskPlanEventView(
        UUID id,
        UUID fromVersionId,
        UUID toVersionId,
        String eventType,
        List<String> changedFields,
        List<String> changedTargets,
        List<String> issueCodes,
        OffsetDateTime createdAt
) {
    public TaskPlanEventView {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
        changedTargets = changedTargets == null ? List.of() : List.copyOf(changedTargets);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }

    public static TaskPlanEventView from(TaskPlanEventRecord record) {
        return new TaskPlanEventView(
                record.id(),
                record.fromVersionId(),
                record.toVersionId(),
                record.eventType(),
                record.changedFields(),
                record.changedTargets(),
                record.issueCodes(),
                record.createdAt());
    }
}
