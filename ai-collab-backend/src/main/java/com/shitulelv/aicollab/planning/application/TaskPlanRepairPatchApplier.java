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
                reject("DUPLICATE_PATCH_TARGET", "MILESTONE", mp.tempKey(), "tempKey");
            }
        }
        Set<String> seenTaskPatches = new HashSet<>();
        for (TaskPlanRepairPatch.TaskPatch tp : patch.taskPatches()) {
            if (!seenTaskPatches.add(tp.tempKey())) {
                reject("DUPLICATE_PATCH_TARGET", "TASK", tp.tempKey(), "tempKey");
            }
        }

        // Validate milestone patches
        for (TaskPlanRepairPatch.MilestonePatch mp : patch.milestonePatches()) {
            if (!draftMilestoneKeys.contains(mp.tempKey())) {
                reject("UNKNOWN_PATCH_TARGET", "MILESTONE", mp.tempKey(), "tempKey");
            }
            if (!scope.targetTempKeys().contains(mp.tempKey())) {
                reject("PATCH_TARGET_OUT_OF_SCOPE", "MILESTONE", mp.tempKey(), "tempKey");
            }
            validateMilestonePatchFields(mp, scope);
            validateSourceRefs(mp.sourceRefs(), validSourceRefs, "MILESTONE", mp.tempKey());
        }

        // Validate task patches
        for (TaskPlanRepairPatch.TaskPatch tp : patch.taskPatches()) {
            if (!draftTaskKeys.contains(tp.tempKey())) {
                reject("UNKNOWN_PATCH_TARGET", "TASK", tp.tempKey(), "tempKey");
            }
            if (!scope.targetTempKeys().contains(tp.tempKey())) {
                reject("PATCH_TARGET_OUT_OF_SCOPE", "TASK", tp.tempKey(), "tempKey");
            }
            validateTaskPatchFields(tp, scope);
            validateMember(tp.suggestedAssigneeId(), validMemberIds, tp.tempKey());
            validateSourceRefs(tp.sourceRefs(), validSourceRefs, "TASK", tp.tempKey());
            validateDependencies(tp.dependencyTempKeys(), draftTaskKeys, tp.tempKey());
        }
    }

    private void validateMilestonePatchFields(TaskPlanRepairPatch.MilestonePatch mp, RepairScope scope) {
        if (mp.description().present() && scope.isLocked(mp.tempKey(), "description")) {
            rejectField("MILESTONE", mp.tempKey(), "description", scope);
        }
        if (mp.targetDate().present() && scope.isLocked(mp.tempKey(), "targetDate")) {
            rejectField("MILESTONE", mp.tempKey(), "targetDate", scope);
        }
        if (mp.sourceRefs().present() && scope.isLocked(mp.tempKey(), "sourceRefs")) {
            rejectField("MILESTONE", mp.tempKey(), "sourceRefs", scope);
        }
    }

    private void validateTaskPatchFields(TaskPlanRepairPatch.TaskPatch tp, RepairScope scope) {
        if (tp.description().present() && scope.isLocked(tp.tempKey(), "description")) {
            rejectField("TASK", tp.tempKey(), "description", scope);
        }
        if (tp.priority().present() && scope.isLocked(tp.tempKey(), "priority")) {
            rejectField("TASK", tp.tempKey(), "priority", scope);
        }
        if (tp.estimatedHours().present() && scope.isLocked(tp.tempKey(), "estimatedHours")) {
            rejectField("TASK", tp.tempKey(), "estimatedHours", scope);
        }
        if (tp.startDate().present() && scope.isLocked(tp.tempKey(), "startDate")) {
            rejectField("TASK", tp.tempKey(), "startDate", scope);
        }
        if (tp.dueDate().present() && scope.isLocked(tp.tempKey(), "dueDate")) {
            rejectField("TASK", tp.tempKey(), "dueDate", scope);
        }
        if (tp.suggestedAssigneeId().present() && scope.isLocked(tp.tempKey(), "suggestedAssigneeId")) {
            rejectField("TASK", tp.tempKey(), "suggestedAssigneeId", scope);
        }
        if (tp.dependencyTempKeys().present() && scope.isLocked(tp.tempKey(), "dependencyTempKeys")) {
            rejectField("TASK", tp.tempKey(), "dependencyTempKeys", scope);
        }
        if (tp.sourceRefs().present() && scope.isLocked(tp.tempKey(), "sourceRefs")) {
            rejectField("TASK", tp.tempKey(), "sourceRefs", scope);
        }
    }

    private void validateMember(PatchValue<UUID> assignee, Set<UUID> validMemberIds, String key) {
        if (assignee.present() && assignee.value() != null && !validMemberIds.contains(assignee.value())) {
            throw new TaskPlanRepairRejectedException(ErrorCode.TASK_ASSIGNEE_NOT_MEMBER,
                    List.of(issue("ASSIGNEE_NOT_PROJECT_MEMBER", "TASK", key, "suggestedAssigneeId")));
        }
    }

    private void validateSourceRefs(PatchValue<List<String>> refs, Set<String> validSourceRefs, String type, String key) {
        if (!refs.present() || refs.value() == null) return;
        for (String ref : refs.value()) {
            if (!validSourceRefs.contains(ref)) {
                reject("UNKNOWN_SOURCE_REF", type, key, "sourceRefs");
            }
        }
    }

    private void validateDependencies(PatchValue<List<String>> deps, Set<String> draftTaskKeys, String selfKey) {
        if (!deps.present() || deps.value() == null) return;
        for (String dep : deps.value()) {
            if (dep.equals(selfKey)) {
                reject("SELF_DEPENDENCY", "TASK", selfKey, "dependencyTempKeys");
            }
            if (!draftTaskKeys.contains(dep)) {
                reject("UNKNOWN_DEPENDENCY", "TASK", selfKey, "dependencyTempKeys");
            }
        }
    }

    private void rejectField(String type, String key, String field, RepairScope scope) {
        reject(scope.lockedFields().contains(field) ? "PATCH_FIELD_LOCKED" : "PATCH_FIELD_NOT_ALLOWED", type, key, field);
    }

    private void reject(String code, String type, String key, String field) {
        throw new TaskPlanRepairRejectedException(ErrorCode.PLAN_VALIDATION_FAILED, List.of(issue(code, type, key, field)));
    }

    private StructuredValidationIssue issue(String code, String type, String key, String field) {
        // Known draft keys may be displayed; bound model-supplied unknown identifiers.
        return new StructuredValidationIssue(code, ValidationIssueSeverity.HARD, type,
                key != null && key.matches("[A-Za-z0-9_-]{1,100}") ? key : null, field, null, Map.of());
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
