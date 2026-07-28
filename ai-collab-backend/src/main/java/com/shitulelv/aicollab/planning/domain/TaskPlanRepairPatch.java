package com.shitulelv.aicollab.planning.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Task 5: Scoped repair patch returned by AI model.
 *
 * Each field uses PatchValue to distinguish omitted (no modify) from null (clear).
 * The patch only modifies fields listed in allowedFields.
 */
public record TaskPlanRepairPatch(
        List<MilestonePatch> milestonePatches,
        List<TaskPatch> taskPatches
) {
    public TaskPlanRepairPatch {
        milestonePatches = milestonePatches == null ? List.of() : List.copyOf(milestonePatches);
        taskPatches = taskPatches == null ? List.of() : List.copyOf(taskPatches);
    }

    public record MilestonePatch(
            String tempKey,
            PatchValue<String> description,
            PatchValue<LocalDate> targetDate,
            PatchValue<List<String>> sourceRefs
    ) {
        public MilestonePatch {
            description = description == null ? PatchValue.absent() : description;
            targetDate = targetDate == null ? PatchValue.absent() : targetDate;
            sourceRefs = sourceRefs == null ? PatchValue.absent() : sourceRefs;
        }
    }

    public record TaskPatch(
            String tempKey,
            PatchValue<String> description,
            PatchValue<String> priority,
            PatchValue<BigDecimal> estimatedHours,
            PatchValue<LocalDate> startDate,
            PatchValue<LocalDate> dueDate,
            PatchValue<UUID> suggestedAssigneeId,
            PatchValue<List<String>> dependencyTempKeys,
            PatchValue<List<String>> sourceRefs
    ) {
        public TaskPatch {
            description = description == null ? PatchValue.absent() : description;
            priority = priority == null ? PatchValue.absent() : priority;
            estimatedHours = estimatedHours == null ? PatchValue.absent() : estimatedHours;
            startDate = startDate == null ? PatchValue.absent() : startDate;
            dueDate = dueDate == null ? PatchValue.absent() : dueDate;
            suggestedAssigneeId = suggestedAssigneeId == null ? PatchValue.absent() : suggestedAssigneeId;
            dependencyTempKeys = dependencyTempKeys == null ? PatchValue.absent() : dependencyTempKeys;
            sourceRefs = sourceRefs == null ? PatchValue.absent() : sourceRefs;
        }
    }
}
