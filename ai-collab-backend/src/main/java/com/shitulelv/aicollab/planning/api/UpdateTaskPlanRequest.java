package com.shitulelv.aicollab.planning.api;

import com.shitulelv.aicollab.planning.domain.PatchValue;
import com.shitulelv.aicollab.planning.domain.TaskPlanRepairPatch;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Task 8: Request body for PATCH editing a plan.
 * Uses PatchValue to distinguish omitted (no modify) from null (clear).
 */
public record UpdateTaskPlanRequest(
        @NotNull UUID baseVersionId,
        long expectedVersionNo,
        PatchValue<String> title,
        PatchValue<String> goal,
        PatchValue<String> constraints,
        List<TaskPlanRepairPatch.MilestonePatch> milestones,
        List<TaskPlanRepairPatch.TaskPatch> tasks
) {
    public UpdateTaskPlanRequest {
        milestones = milestones == null ? List.of() : List.copyOf(milestones);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
