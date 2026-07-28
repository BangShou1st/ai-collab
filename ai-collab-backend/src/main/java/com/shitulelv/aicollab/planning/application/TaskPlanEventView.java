package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRecord;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
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
        return from(record, inferChangedTargets(record.changedFields()));
    }

    static TaskPlanEventView from(TaskPlanEventRecord record, List<String> changedTargets) {
        return new TaskPlanEventView(
                record.id(),
                record.fromVersionId(),
                record.toVersionId(),
                record.eventType(),
                record.changedFields(),
                changedTargets,
                record.issueCodes(),
                record.createdAt());
    }

    private static List<String> inferChangedTargets(List<String> fields) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String field : fields == null ? List.<String>of() : fields) {
            String[] segments = field.split("\\.");
            if (segments.length >= 2 && ("tasks".equals(segments[0]) || "milestones".equals(segments[0]))) {
                targets.add(("tasks".equals(segments[0]) ? "TASK:" : "MILESTONE:") + segments[1]);
            }
        }
        return List.copyOf(targets);
    }
}
