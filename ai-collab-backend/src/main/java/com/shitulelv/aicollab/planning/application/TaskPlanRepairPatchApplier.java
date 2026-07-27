package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.domain.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Task 5: Apply scoped repair patch to a draft with safety validation.
 *
 * Pre-apply checks:
 * - Target tempKey must exist in draft
 * - Cannot modify locked fields
 * - Cannot modify unauthorized fields
 * - Cannot add cross-project members
 * - Cannot add unknown sources
 * - Cannot modify skeleton identity
 * - Cannot add/remove non-target tasks
 * - No duplicate patch targets
 */
@Component
public class TaskPlanRepairPatchApplier {

    public TaskPlanDraft apply(TaskPlanDraft draft, TaskPlanRepairPatch patch, RepairScope scope,
                               Set<UUID> validMemberIds, Set<String> validSourceRefs) {
        validatePatch(patch, draft, scope, validMemberIds, validSourceRefs);

        // Apply milestone patches
        Map<String, PlanMilestone> milestoneMap = new LinkedHashMap<>();
        for (PlanMilestone m : draft.milestones()) {
            milestoneMap.put(m.tempKey(), m);
        }
        for (TaskPlanRepairPatch.MilestonePatch mp : patch.milestonePatches()) {
            PlanMilestone original = milestoneMap.get(mp.tempKey());
            if (original == null) continue; // validated above
            milestoneMap.put(mp.tempKey(), applyMilestonePatch(original, mp, scope));
        }

        // Apply task patches
        Map<String, PlanTask> taskMap = new LinkedHashMap<>();
        for (PlanTask t : draft.tasks()) {
            taskMap.put(t.tempKey(), t);
        }
        for (TaskPlanRepairPatch.TaskPatch tp : patch.taskPatches()) {
            PlanTask original = taskMap.get(tp.tempKey());
            if (original == null) continue; // validated above
            taskMap.put(tp.tempKey(), applyTaskPatch(original, tp, scope));
        }

        return new TaskPlanDraft(
                draft.summary(),
                draft.assumptions(),
                draft.risks(),
                new ArrayList<>(milestoneMap.values()),
                new ArrayList<>(taskMap.values()),
                draft.sources());
    }

    private void validatePatch(TaskPlanRepairPatch patch, TaskPlanDraft draft,
                               RepairScope scope, Set<UUID> validMemberIds, Set<String> validSourceRefs) {
        Set<String> draftMilestoneKeys = draft.milestones().stream()
                .map(PlanMilestone::tempKey).collect(Collectors.toSet());
        Set<String> draftTaskKeys = draft.tasks().stream()
                .map(PlanTask::tempKey).collect(Collectors.toSet());

        // Check duplicate patch targets
        Set<String> seenMilestonePatches = new HashSet<>();
        for (TaskPlanRepairPatch.MilestonePatch mp : patch.milestonePatches()) {
            if (!seenMilestonePatches.add(mp.tempKey())) {
                throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
            }
        }
        Set<String> seenTaskPatches = new HashSet<>();
        for (TaskPlanRepairPatch.TaskPatch tp : patch.taskPatches()) {
            if (!seenTaskPatches.add(tp.tempKey())) {
                throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
            }
        }

        // Validate milestone patches
        for (TaskPlanRepairPatch.MilestonePatch mp : patch.milestonePatches()) {
            if (!draftMilestoneKeys.contains(mp.tempKey())) {
                throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
            }
            if (!scope.targetTempKeys().contains(mp.tempKey())) {
                throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
            }
            validateMilestonePatchFields(mp, scope);
            validateSourceRefs(mp.sourceRefs(), validSourceRefs);
        }

        // Validate task patches
        for (TaskPlanRepairPatch.TaskPatch tp : patch.taskPatches()) {
            if (!draftTaskKeys.contains(tp.tempKey())) {
                throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
            }
            if (!scope.targetTempKeys().contains(tp.tempKey())) {
                throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
            }
            validateTaskPatchFields(tp, scope);
            validateMember(tp.suggestedAssigneeId(), validMemberIds);
            validateSourceRefs(tp.sourceRefs(), validSourceRefs);
            validateDependencies(tp.dependencyTempKeys(), draftTaskKeys, tp.tempKey());
        }
    }

    private void validateMilestonePatchFields(TaskPlanRepairPatch.MilestonePatch mp, RepairScope scope) {
        if (mp.description().present() && scope.isLocked(mp.tempKey(), "description")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
        if (mp.targetDate().present() && scope.isLocked(mp.tempKey(), "targetDate")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
        if (mp.sourceRefs().present() && scope.isLocked(mp.tempKey(), "sourceRefs")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
    }

    private void validateTaskPatchFields(TaskPlanRepairPatch.TaskPatch tp, RepairScope scope) {
        if (tp.description().present() && scope.isLocked(tp.tempKey(), "description")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
        if (tp.priority().present() && scope.isLocked(tp.tempKey(), "priority")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
        if (tp.estimatedHours().present() && scope.isLocked(tp.tempKey(), "estimatedHours")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
        if (tp.startDate().present() && scope.isLocked(tp.tempKey(), "startDate")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
        if (tp.dueDate().present() && scope.isLocked(tp.tempKey(), "dueDate")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
        if (tp.suggestedAssigneeId().present() && scope.isLocked(tp.tempKey(), "suggestedAssigneeId")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
        if (tp.dependencyTempKeys().present() && scope.isLocked(tp.tempKey(), "dependencyTempKeys")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
        if (tp.sourceRefs().present() && scope.isLocked(tp.tempKey(), "sourceRefs")) {
            throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
        }
    }

    private void validateMember(PatchValue<UUID> assignee, Set<UUID> validMemberIds) {
        if (assignee.present() && assignee.value() != null && !validMemberIds.contains(assignee.value())) {
            throw new BusinessException(ErrorCode.TASK_ASSIGNEE_NOT_MEMBER);
        }
    }

    private void validateSourceRefs(PatchValue<List<String>> refs, Set<String> validSourceRefs) {
        if (!refs.present() || refs.value() == null) return;
        for (String ref : refs.value()) {
            if (!validSourceRefs.contains(ref)) {
                throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
            }
        }
    }

    private void validateDependencies(PatchValue<List<String>> deps, Set<String> draftTaskKeys, String selfKey) {
        if (!deps.present() || deps.value() == null) return;
        for (String dep : deps.value()) {
            if (dep.equals(selfKey)) {
                throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
            }
            if (!draftTaskKeys.contains(dep)) {
                throw new BusinessException(ErrorCode.PLAN_VALIDATION_FAILED);
            }
        }
    }

    private PlanMilestone applyMilestonePatch(PlanMilestone original, TaskPlanRepairPatch.MilestonePatch mp, RepairScope scope) {
        return new PlanMilestone(
                original.tempKey(),
                original.title(),
                original.objective(),
                mp.description().present() ? mp.description().value() : original.description(),
                mp.targetDate().present() ? mp.targetDate().value() : original.targetDate(),
                original.sortOrder(),
                mp.sourceRefs().present() ? mp.sourceRefs().value() : original.sourceRefs());
    }

    private PlanTask applyTaskPatch(PlanTask original, TaskPlanRepairPatch.TaskPatch tp, RepairScope scope) {
        return new PlanTask(
                original.tempKey(),
                original.milestoneTempKey(),
                original.title(),
                original.objective(),
                tp.description().present() ? tp.description().value() : original.description(),
                tp.priority().present() ? tp.priority().value() : original.priority(),
                tp.estimatedHours().present() ? tp.estimatedHours().value() : original.estimatedHours(),
                tp.startDate().present() ? tp.startDate().value() : original.startDate(),
                tp.dueDate().present() ? tp.dueDate().value() : original.dueDate(),
                tp.suggestedAssigneeId().present() ? tp.suggestedAssigneeId().value() : original.suggestedAssigneeId(),
                original.assigneeId(),
                tp.dependencyTempKeys().present() ? tp.dependencyTempKeys().value() : original.dependencyTempKeys(),
                tp.sourceRefs().present() ? tp.sourceRefs().value() : original.sourceRefs(),
                original.sortOrder());
    }
}
