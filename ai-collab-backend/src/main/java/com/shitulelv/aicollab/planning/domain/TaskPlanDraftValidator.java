package com.shitulelv.aicollab.planning.domain;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TaskPlanDraftValidator {

    public enum ValidationMode {
        /** Complete draft (AI_COMPLETE, MANUAL_EDIT, RESTORED, CONFIRM) — all fields required. */
        COMPLETE,
        /** AI skeleton output — only identity fields required, detail fields may be null. */
        AI_SKELETON,
        /** AI detail output before merge — only supplementary fields checked. */
        AI_DETAIL
    }

    public ValidationResult validate(ValidationContext context, TaskPlanDraft draft) {
        return validate(context, draft, ValidationMode.COMPLETE);
    }

    public ValidationResult validate(ValidationContext context, TaskPlanDraft draft, ValidationMode mode) {
        return validate(context, draft, mode, false);
    }

    public ValidationResult validate(ValidationContext context, TaskPlanDraft draft, ValidationMode mode, boolean aiGenerated) {
        Set<String> errors = new HashSet<>();
        Set<String> warnings = new HashSet<>();
        if (outside(context.planStartDate(), context.projectStartDate(), context.projectDueDate())
                || outside(context.planDueDate(), context.projectStartDate(), context.projectDueDate())
                || context.planStartDate().isAfter(context.planDueDate())) {
            errors.add("PLAN_DATE_OUTSIDE_PROJECT");
        }
        if (draft.milestones().size() > 8) errors.add("MILESTONE_LIMIT_EXCEEDED");
        if (draft.milestones().isEmpty()) errors.add("MILESTONE_REQUIRED");
        if (draft.tasks().size() > 40 || draft.tasks().size() > context.maxTaskCount()) {
            errors.add("TASK_LIMIT_EXCEEDED");
        }
        if (draft.tasks().isEmpty()) errors.add("TASK_REQUIRED");
        if (draft.summary() == null || draft.summary().isBlank()) errors.add("SUMMARY_REQUIRED");
        if (draft.summary() != null && draft.summary().length() > 2000) errors.add("SUMMARY_TOO_LONG");
        if (draft.assumptions() == null || draft.assumptions().size() > 20) errors.add("ASSUMPTION_LIMIT_EXCEEDED");
        if (draft.risks() == null || draft.risks().size() > 20) errors.add("RISK_LIMIT_EXCEEDED");

        Map<String, PlanMilestone> milestones = new HashMap<>();
        Set<String> allKeys = new HashSet<>();
        for (PlanMilestone milestone : draft.milestones()) {
            if (milestone == null) {
                errors.add("MILESTONE_NULL");
                continue;
            }
            if (blank(milestone.tempKey()) || blank(milestone.title()) || blank(milestone.objective())
                    || milestone.title().length() > 100 || milestone.objective().length() > 1000) {
                errors.add("MILESTONE_TEXT_INVALID");
            }
            if (!allKeys.add(milestone.tempKey())) errors.add("TEMP_KEY_DUPLICATE");
            milestones.put(milestone.tempKey(), milestone);
            if (outside(milestone.targetDate(), context.planStartDate(), context.planDueDate())) {
                errors.add("MILESTONE_DATE_OUTSIDE_PLAN");
            }
            if (milestone.sourceRefs().size() > 5) errors.add("SOURCE_REF_LIMIT_EXCEEDED");
            if (milestone.sortOrder() < 0) errors.add("SORT_ORDER_INVALID");
        }
        Map<String, PlanTask> tasks = new HashMap<>();
        Set<String> sourceRefs = new HashSet<>();
        if (draft.sources().size() > 12) errors.add("SOURCE_LIMIT_EXCEEDED");
        for (PlanSource source : draft.sources()) {
            if (source == null || blank(source.ref())) errors.add("SOURCE_INVALID");
            else if (!source.ref().matches("^S\\d{1,2}$")) errors.add("SOURCE_REF_FORMAT_INVALID");
            else sourceRefs.add(source.ref());
        }
        Set<String> normalizedTitles = new HashSet<>();
        boolean skeletonMode = (mode == ValidationMode.AI_SKELETON);
        boolean detailMode = (mode == ValidationMode.AI_DETAIL);
        for (PlanTask task : draft.tasks()) {
            if (task == null) {
                errors.add("TASK_NULL");
                continue;
            }
            if (blank(task.tempKey()) || blank(task.milestoneTempKey()) || blank(task.title())
                    || blank(task.objective())
                    || task.title().length() > 160 || task.objective().length() > 1000) errors.add("TASK_TEXT_INVALID");
            // Skeleton mode: description may be null; complete mode: description required
            if (!skeletonMode) {
                if (blank(task.description()) || task.description().length() > 4000) errors.add("TASK_TEXT_INVALID");
            }
            if (!allKeys.add(task.tempKey())) errors.add("TEMP_KEY_DUPLICATE");
            tasks.put(task.tempKey(), task);
            if (!milestones.containsKey(task.milestoneTempKey())) errors.add("MILESTONE_REF_INVALID");
            if (aiGenerated && task.assigneeId() != null) errors.add("AI_GENERATED_ASSIGNEE_NOT_ALLOWED");
            validateTask(context, task, sourceRefs, errors, warnings, skeletonMode);
            if (!blank(task.title())) {
                String normalized = task.title().trim().toLowerCase(Locale.ROOT);
                if (!normalizedTitles.add(normalized)) warnings.add("DUPLICATE_TITLE");
                if (context.existingTitles().contains(normalized)) warnings.add("EXISTING_TITLE_SIMILAR");
            }
        }
        for (PlanMilestone milestone : draft.milestones()) {
            if (milestone != null) validateSourceRefs(milestone.sourceRefs(), sourceRefs, errors);
        }
        validateDependencies(tasks, errors);
        return new ValidationResult(sorted(errors), sorted(warnings));
    }

    /**
     * R1: Comprehensive skeleton preservation check.
     * Verifies ALL skeleton fields — not just tempKey+title+objective.
     * Milestones: tempKey, title, objective, targetDate, sortOrder, count.
     * Tasks: tempKey, milestoneTempKey, title, objective, sortOrder, count.
     * Top-level: summary, assumptions, risks.
     */
    public ValidationResult validateSkeletonPreserved(TaskPlanDraft skeleton, TaskPlanDraft detail) {
        if (skeleton.milestones().size() != detail.milestones().size()
                || skeleton.tasks().size() != detail.tasks().size()) {
            return new ValidationResult(List.of("SKELETON_MUTATED"), List.of());
        }
        if (skeleton.milestones().stream().anyMatch(java.util.Objects::isNull)
                || detail.milestones().stream().anyMatch(java.util.Objects::isNull)
                || skeleton.tasks().stream().anyMatch(java.util.Objects::isNull)
                || detail.tasks().stream().anyMatch(java.util.Objects::isNull)) {
            return new ValidationResult(List.of("SKELETON_MUTATED"), List.of());
        }
        // Check top-level fields
        boolean same = java.util.Objects.equals(skeleton.summary(), detail.summary())
                && java.util.Objects.equals(skeleton.assumptions(), detail.assumptions())
                && java.util.Objects.equals(skeleton.risks(), detail.risks())
                && milestoneFullIdentities(skeleton).equals(milestoneFullIdentities(detail))
                && taskFullIdentities(skeleton).equals(taskFullIdentities(detail));
        return same ? new ValidationResult(List.of(), List.of())
                : new ValidationResult(List.of("SKELETON_MUTATED"), List.of());
    }

    private void validateTask(ValidationContext context, PlanTask task, Set<String> sourceRefs,
                              Set<String> errors, Set<String> warnings, boolean skeletonMode) {
        if (task.dependencyTempKeys().size() > 5) errors.add("DEPENDENCY_LIMIT_EXCEEDED");
        if (task.sourceRefs().size() > 5) errors.add("SOURCE_REF_LIMIT_EXCEEDED");
        validateSourceRefs(task.sourceRefs(), sourceRefs, errors);
        // Skeleton mode: skip detail-specific checks (dates, hours, priority, assignee)
        if (!skeletonMode) {
            if (outside(task.startDate(), context.planStartDate(), context.planDueDate())
                    || outside(task.dueDate(), context.planStartDate(), context.planDueDate())
                    || task.startDate() != null && task.dueDate() != null && task.startDate().isAfter(task.dueDate())) {
                errors.add("TASK_DATE_INVALID");
            }
            if (task.estimatedHours() != null
                    && (task.estimatedHours().compareTo(BigDecimal.ZERO) <= 0
                    || task.estimatedHours().compareTo(BigDecimal.valueOf(80)) > 0)) {
                errors.add("ESTIMATED_HOURS_INVALID");
            }
            if (task.priority() == null || !Set.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(task.priority())) {
                errors.add("TASK_PRIORITY_INVALID");
            }
            if (task.suggestedAssigneeId() != null
                    && !context.projectMemberIds().contains(task.suggestedAssigneeId())
                    || task.assigneeId() != null && !context.projectMemberIds().contains(task.assigneeId())) {
                errors.add("ASSIGNEE_NOT_PROJECT_MEMBER");
            }
            if (task.assigneeId() == null) warnings.add("TASK_UNASSIGNED");
            if (task.sourceRefs().isEmpty()) warnings.add("AI_SUGGESTION_WITHOUT_SOURCE");
        }
        if (task.sortOrder() < 0) errors.add("SORT_ORDER_INVALID");
    }

    private void validateDependencies(Map<String, PlanTask> tasks, Set<String> errors) {
        for (PlanTask task : tasks.values()) {
            for (String dependency : task.dependencyTempKeys()) {
                if (dependency == null) {
                    errors.add("DEPENDENCY_REF_INVALID");
                    continue;
                }
                if (dependency.equals(task.tempKey())) errors.add("SELF_DEPENDENCY");
                PlanTask prerequisite = tasks.get(dependency);
                if (prerequisite == null) {
                    errors.add("DEPENDENCY_REF_INVALID");
                } else if (prerequisite.dueDate() != null && task.startDate() != null
                        && prerequisite.dueDate().isAfter(task.startDate())) {
                    errors.add("DEPENDENCY_DATE_CONFLICT");
                }
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> path = new HashSet<>();
        for (String key : tasks.keySet()) {
            if (hasCycle(key, tasks, visited, path)) {
                errors.add("DEPENDENCY_CYCLE");
                break;
            }
        }
    }

    private boolean hasCycle(String key, Map<String, PlanTask> tasks, Set<String> visited, Set<String> path) {
        if (path.contains(key)) return true;
        if (!visited.add(key)) return false;
        path.add(key);
        PlanTask task = tasks.get(key);
        if (task != null) {
            for (String dependency : task.dependencyTempKeys()) {
                if (tasks.containsKey(dependency) && hasCycle(dependency, tasks, visited, path)) return true;
            }
        }
        path.remove(key);
        return false;
    }

    private static void validateSourceRefs(List<String> refs, Set<String> valid, Set<String> errors) {
        for (String ref : refs) {
            if (ref == null || !ref.matches("^S\\d{1,2}$")) errors.add("SOURCE_REF_FORMAT_INVALID");
            else if (!valid.contains(ref)) errors.add("SOURCE_REF_INVALID");
        }
        if (refs.stream().distinct().count() < refs.size()) errors.add("SOURCE_REF_DUPLICATE");
    }

    private static boolean outside(LocalDate date, LocalDate start, LocalDate due) {
        return date != null && (start != null && date.isBefore(start) || due != null && date.isAfter(due));
    }

    /** R1: Full identity check including targetDate and sortOrder. */
    private static List<String> milestoneFullIdentities(TaskPlanDraft draft) {
        return draft.milestones().stream()
                .map(item -> item.tempKey() + "\0" + item.title() + "\0" + item.objective()
                        + "\0" + item.targetDate() + "\0" + item.sortOrder()).toList();
    }

    /** R1: Full identity check including milestoneTempKey and sortOrder. */
    private static List<String> taskFullIdentities(TaskPlanDraft draft) {
        return draft.tasks().stream()
                .map(item -> item.tempKey() + "\0" + item.milestoneTempKey() + "\0"
                        + item.title() + "\0" + item.objective() + "\0" + item.sortOrder()).toList();
    }

    private static List<String> sorted(Set<String> values) {
        List<String> result = new ArrayList<>(values);
        result.sort(String::compareTo);
        return result;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
