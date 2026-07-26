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
    public ValidationResult validate(ValidationContext context, TaskPlanDraft draft) {
        Set<String> errors = new HashSet<>();
        Set<String> warnings = new HashSet<>();
        if (outside(context.planStartDate(), context.projectStartDate(), context.projectDueDate())
                || outside(context.planDueDate(), context.projectStartDate(), context.projectDueDate())
                || context.planStartDate().isAfter(context.planDueDate())) {
            errors.add("PLAN_DATE_OUTSIDE_PROJECT");
        }
        if (draft.milestones().size() > 8) errors.add("MILESTONE_LIMIT_EXCEEDED");
        if (draft.tasks().size() > 40 || draft.tasks().size() > context.maxTaskCount()) {
            errors.add("TASK_LIMIT_EXCEEDED");
        }

        Map<String, PlanMilestone> milestones = new HashMap<>();
        Set<String> allKeys = new HashSet<>();
        for (PlanMilestone milestone : draft.milestones()) {
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
        }
        Map<String, PlanTask> tasks = new HashMap<>();
        Set<String> sourceRefs = new HashSet<>();
        draft.sources().forEach(source -> sourceRefs.add(source.ref()));
        Set<String> normalizedTitles = new HashSet<>();
        for (PlanTask task : draft.tasks()) {
            if (blank(task.tempKey()) || blank(task.milestoneTempKey()) || blank(task.title())
                    || blank(task.objective()) || blank(task.description())
                    || task.title().length() > 160 || task.objective().length() > 1000
                    || task.description().length() > 4000) errors.add("TASK_TEXT_INVALID");
            if (!allKeys.add(task.tempKey())) errors.add("TEMP_KEY_DUPLICATE");
            tasks.put(task.tempKey(), task);
            if (!milestones.containsKey(task.milestoneTempKey())) errors.add("MILESTONE_REF_INVALID");
            validateTask(context, task, sourceRefs, errors, warnings);
            if (!blank(task.title())) {
                String normalized = task.title().trim().toLowerCase(Locale.ROOT);
                if (!normalizedTitles.add(normalized)) warnings.add("DUPLICATE_TITLE");
                if (context.existingTitles().contains(normalized)) warnings.add("EXISTING_TITLE_SIMILAR");
            }
        }
        for (PlanMilestone milestone : draft.milestones()) {
            validateSourceRefs(milestone.sourceRefs(), sourceRefs, errors);
        }
        validateDependencies(tasks, errors);
        return new ValidationResult(sorted(errors), sorted(warnings));
    }

    public ValidationResult validateSkeletonPreserved(TaskPlanDraft skeleton, TaskPlanDraft detail) {
        boolean same = skeleton.milestones().size() == detail.milestones().size()
                && skeleton.tasks().size() == detail.tasks().size()
                && milestoneIdentities(skeleton).equals(milestoneIdentities(detail))
                && taskIdentities(skeleton).equals(taskIdentities(detail));
        return same ? new ValidationResult(List.of(), List.of())
                : new ValidationResult(List.of("SKELETON_MUTATED"), List.of());
    }

    private void validateTask(ValidationContext context, PlanTask task, Set<String> sourceRefs,
                              Set<String> errors, Set<String> warnings) {
        if (task.dependencyTempKeys().size() > 5) errors.add("DEPENDENCY_LIMIT_EXCEEDED");
        if (task.sourceRefs().size() > 5) errors.add("SOURCE_REF_LIMIT_EXCEEDED");
        validateSourceRefs(task.sourceRefs(), sourceRefs, errors);
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

    private void validateDependencies(Map<String, PlanTask> tasks, Set<String> errors) {
        for (PlanTask task : tasks.values()) {
            for (String dependency : task.dependencyTempKeys()) {
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
        if (refs.stream().anyMatch(ref -> !valid.contains(ref))) errors.add("SOURCE_REF_INVALID");
    }

    private static boolean outside(LocalDate date, LocalDate start, LocalDate due) {
        return date != null && (start != null && date.isBefore(start) || due != null && date.isAfter(due));
    }

    private static List<String> milestoneIdentities(TaskPlanDraft draft) {
        return draft.milestones().stream()
                .map(item -> item.tempKey() + "\0" + item.title() + "\0" + item.objective()).toList();
    }

    private static List<String> taskIdentities(TaskPlanDraft draft) {
        return draft.tasks().stream()
                .map(item -> item.tempKey() + "\0" + item.milestoneTempKey() + "\0"
                        + item.title() + "\0" + item.objective()).toList();
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
