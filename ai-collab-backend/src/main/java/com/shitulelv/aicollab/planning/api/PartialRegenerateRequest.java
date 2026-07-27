package com.shitulelv.aicollab.planning.api;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task 9: Request body for partial regeneration.
 * Model returns only a patch for selected fields/tasks.
 */
public record PartialRegenerateRequest(
        @NotNull UUID baseVersionId,
        List<String> targetTempKeys,
        Set<String> allowedFields,
        Set<String> lockedFields,
        List<UUID> issueIds,
        String mode
) {
    public static final String REPAIR_ALL_ISSUES = "REPAIR_ALL_ISSUES";
    public static final String REPAIR_DATES_AND_DEPENDENCIES = "REPAIR_DATES_AND_DEPENDENCIES";
    public static final String REGENERATE_SELECTED_TASK_DETAILS = "REGENERATE_SELECTED_TASK_DETAILS";
    public static final String RESCHEDULE_UNLOCKED_TASKS = "RESCHEDULE_UNLOCKED_TASKS";
    public static final String APPLY_UPDATED_CONSTRAINTS = "APPLY_UPDATED_CONSTRAINTS";

    public PartialRegenerateRequest {
        targetTempKeys = targetTempKeys == null ? List.of() : List.copyOf(targetTempKeys);
        allowedFields = allowedFields == null ? Set.of() : Set.copyOf(allowedFields);
        lockedFields = lockedFields == null ? Set.of() : Set.copyOf(lockedFields);
        issueIds = issueIds == null ? List.of() : List.copyOf(issueIds);
    }
}
