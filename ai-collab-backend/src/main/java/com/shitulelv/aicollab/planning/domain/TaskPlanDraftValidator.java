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
        return assess(context, draft, mode, aiGenerated).toFlat();
    }

    /**
     * Production structured validation entry point. Rules create located issues at the point
     * where the invalid value is observed. The flat ValidationResult is derived from this
     * assessment and is never used as the source of API or persistence issues.
     */
    public ValidationAssessment assess(ValidationContext context, TaskPlanDraft draft,
                                       ValidationMode mode, boolean aiGenerated) {
        List<StructuredValidationIssue> issues = new ArrayList<>();
        if (outside(context.planStartDate(), context.projectStartDate(), context.projectDueDate())
                || outside(context.planDueDate(), context.projectStartDate(), context.projectDueDate())
                || context.planStartDate().isAfter(context.planDueDate())) {
            add(issues, "PLAN_DATE_OUTSIDE_PROJECT", "PLAN", null, "dateRange", null, Map.of());
        }
        if (draft.milestones().size() > 8) {
            add(issues, "MILESTONE_LIMIT_EXCEEDED", "PLAN", null, "milestones", null, Map.of("maximum", 8));
        }
        if (draft.milestones().isEmpty()) {
            add(issues, "MILESTONE_REQUIRED", "PLAN", null, "milestones", null, Map.of());
        }
        if (draft.tasks().size() > 40 || draft.tasks().size() > context.maxTaskCount()) {
            add(issues, "TASK_LIMIT_EXCEEDED", "PLAN", null, "tasks", null,
                    Map.of("maximum", Math.min(40, context.maxTaskCount())));
        }
        if (draft.tasks().isEmpty()) {
            add(issues, "TASK_REQUIRED", "PLAN", null, "tasks", null, Map.of());
        }
        if (draft.summary() == null || draft.summary().isBlank()) {
            add(issues, "SUMMARY_REQUIRED", "PLAN", null, "summary", null, Map.of());
        }
        if (draft.summary() != null && draft.summary().length() > 2000) {
            add(issues, "SUMMARY_TOO_LONG", "PLAN", null, "summary", null, Map.of("maximum", 2000));
        }
        if (draft.assumptions() == null || draft.assumptions().size() > 20) {
            add(issues, "ASSUMPTION_LIMIT_EXCEEDED", "PLAN", null, "assumptions", null, Map.of("maximum", 20));
        }
        if (draft.risks() == null || draft.risks().size() > 20) {
            add(issues, "RISK_LIMIT_EXCEEDED", "PLAN", null, "risks", null, Map.of("maximum", 20));
        }

        Map<String, PlanMilestone> milestones = new HashMap<>();
        Set<String> allKeys = new HashSet<>();
        for (PlanMilestone milestone : draft.milestones()) {
            if (milestone == null) {
                add(issues, "MILESTONE_NULL", "PLAN", null, "milestones", null, Map.of());
                continue;
            }
            if (blank(milestone.tempKey()) || blank(milestone.title()) || blank(milestone.objective())
                    || milestone.title().length() > 100 || milestone.objective().length() > 1000) {
                add(issues, "MILESTONE_TEXT_INVALID", "MILESTONE", milestone.tempKey(),
                        "title", null, Map.of());
            }
            if (!allKeys.add(milestone.tempKey())) {
                add(issues, "TEMP_KEY_DUPLICATE", "MILESTONE", milestone.tempKey(),
                        "tempKey", milestone.tempKey(), Map.of());
            }
            milestones.put(milestone.tempKey(), milestone);
            if (outside(milestone.targetDate(), context.planStartDate(), context.planDueDate())) {
                add(issues, "MILESTONE_DATE_OUTSIDE_PLAN", "MILESTONE", milestone.tempKey(),
                        "targetDate", null, Map.of("targetDate", milestone.targetDate()));
            }
            if (milestone.sourceRefs().size() > 5) {
                add(issues, "SOURCE_REF_LIMIT_EXCEEDED", "MILESTONE", milestone.tempKey(),
                        "sourceRefs", null, Map.of("maximum", 5));
            }
            if (milestone.sortOrder() < 0) {
                add(issues, "SORT_ORDER_INVALID", "MILESTONE", milestone.tempKey(),
                        "sortOrder", null, Map.of("minimum", 0));
            }
        }

        Map<String, PlanTask> tasks = new HashMap<>();
        Set<String> sourceRefs = new HashSet<>();
        if (draft.sources().size() > 12) {
            add(issues, "SOURCE_LIMIT_EXCEEDED", "PLAN", null, "sources", null, Map.of("maximum", 12));
        }
        Set<String> sourceRefValues = new HashSet<>();
        for (PlanSource source : draft.sources()) {
            if (source == null || blank(source.ref())) {
                add(issues, "SOURCE_INVALID", "PLAN", null, "sources", null, Map.of());
                continue;
            }
            if (!source.ref().matches("^S([1-9]|1[0-2])$")) {
                add(issues, "SOURCE_REF_FORMAT_INVALID", "SOURCE", source.ref(),
                        "ref", null, Map.of("sourceRef", source.ref()));
                continue;
            }
            if (!sourceRefValues.add(source.ref())) {
                add(issues, "SOURCE_REF_DUPLICATE", "SOURCE", source.ref(),
                        "ref", source.ref(), Map.of("sourceRef", source.ref()));
            }
            sourceRefs.add(source.ref());
        }

        for (PlanMilestone milestone : draft.milestones()) {
            if (milestone != null) {
                validateLocatedSourceRefs(issues, "MILESTONE", milestone.tempKey(),
                        milestone.sourceRefs(), sourceRefs);
            }
        }

        Set<String> normalizedTitles = new HashSet<>();
        boolean skeletonMode = (mode == ValidationMode.AI_SKELETON);
        for (PlanTask task : draft.tasks()) {
            if (task == null) {
                add(issues, "TASK_NULL", "PLAN", null, "tasks", null, Map.of());
                continue;
            }
            if (blank(task.tempKey()) || blank(task.milestoneTempKey()) || blank(task.title())
                    || blank(task.objective())
                    || task.title().length() > 160 || task.objective().length() > 1000
                    || !skeletonMode && (blank(task.description()) || task.description().length() > 4000)) {
                add(issues, "TASK_TEXT_INVALID", "TASK", task.tempKey(), "title", null, Map.of());
            }
            if (!allKeys.add(task.tempKey())) {
                add(issues, "TEMP_KEY_DUPLICATE", "TASK", task.tempKey(),
                        "tempKey", task.tempKey(), Map.of());
            }
            tasks.put(task.tempKey(), task);
            if (!milestones.containsKey(task.milestoneTempKey())) {
                add(issues, "MILESTONE_REF_INVALID", "TASK", task.tempKey(),
                        "milestoneTempKey", task.milestoneTempKey(), Map.of());
            }
            if (aiGenerated && task.assigneeId() != null) {
                add(issues, "AI_GENERATED_ASSIGNEE_NOT_ALLOWED", "TASK", task.tempKey(),
                        "assigneeId", null, Map.of());
            }
            if (task.dependencyTempKeys().size() > 5) {
                add(issues, "DEPENDENCY_LIMIT_EXCEEDED", "TASK", task.tempKey(),
                        "dependencyTempKeys", null, Map.of("maximum", 5));
            }
            if (task.sourceRefs().size() > 5) {
                add(issues, "SOURCE_REF_LIMIT_EXCEEDED", "TASK", task.tempKey(),
                        "sourceRefs", null, Map.of("maximum", 5));
            }
            validateLocatedSourceRefs(issues, "TASK", task.tempKey(), task.sourceRefs(), sourceRefs);
            if (!skeletonMode) {
                if (outside(task.startDate(), context.planStartDate(), context.planDueDate())
                        || outside(task.dueDate(), context.planStartDate(), context.planDueDate())
                        || task.startDate() != null && task.dueDate() != null
                        && task.startDate().isAfter(task.dueDate())) {
                    String field = outside(task.startDate(), context.planStartDate(), context.planDueDate())
                            ? "startDate" : "dueDate";
                    add(issues, "TASK_DATE_INVALID", "TASK", task.tempKey(), field, null,
                            dateDetails(task.startDate(), task.dueDate()));
                }
                if (task.estimatedHours() != null
                        && (task.estimatedHours().compareTo(new BigDecimal("0.5")) < 0
                        || task.estimatedHours().compareTo(BigDecimal.valueOf(80)) > 0)) {
                    add(issues, "ESTIMATED_HOURS_INVALID", "TASK", task.tempKey(),
                            "estimatedHours", null,
                            Map.of("minimum", new BigDecimal("0.5"), "maximum", 80));
                }
                if (task.priority() == null
                        || !Set.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(task.priority())) {
                    add(issues, "TASK_PRIORITY_INVALID", "TASK", task.tempKey(), "priority", null,
                            Map.of("allowedValues", List.of("LOW", "MEDIUM", "HIGH", "URGENT")));
                }
                if (task.suggestedAssigneeId() != null
                        && !context.projectMemberIds().contains(task.suggestedAssigneeId())) {
                    add(issues, "ASSIGNEE_NOT_PROJECT_MEMBER", "TASK", task.tempKey(),
                            "suggestedAssigneeId", null, Map.of());
                }
                if (task.assigneeId() != null && !context.projectMemberIds().contains(task.assigneeId())) {
                    add(issues, "ASSIGNEE_NOT_PROJECT_MEMBER", "TASK", task.tempKey(),
                            "assigneeId", null, Map.of());
                }
                if (task.assigneeId() == null) {
                    add(issues, "TASK_UNASSIGNED", "TASK", task.tempKey(), "assigneeId", null, Map.of());
                }
                if (task.sourceRefs().isEmpty()) {
                    add(issues, "AI_SUGGESTION_WITHOUT_SOURCE", "TASK", task.tempKey(),
                            "sourceRefs", null, Map.of());
                }
            }
            if (task.sortOrder() < 0) {
                add(issues, "SORT_ORDER_INVALID", "TASK", task.tempKey(),
                        "sortOrder", null, Map.of("minimum", 0));
            }
            if (!blank(task.title())) {
                String normalized = task.title().trim().toLowerCase(Locale.ROOT);
                if (!normalizedTitles.add(normalized)) {
                    add(issues, "DUPLICATE_TITLE", "TASK", task.tempKey(), "title", null, Map.of());
                }
                if (context.existingTitles().contains(normalized)) {
                    add(issues, "EXISTING_TITLE_SIMILAR", "TASK", task.tempKey(), "title", null, Map.of());
                }
            }
        }

        for (PlanTask task : tasks.values()) {
            Set<String> seen = new HashSet<>();
            for (String dependencyKey : task.dependencyTempKeys()) {
                if (dependencyKey == null || !tasks.containsKey(dependencyKey)) {
                    add(issues, "DEPENDENCY_REF_INVALID", "TASK", task.tempKey(),
                            "dependencyTempKeys", dependencyKey, Map.of());
                    continue;
                }
                if (!seen.add(dependencyKey)) {
                    add(issues, "DEPENDENCY_DUPLICATE", "TASK", task.tempKey(),
                            "dependencyTempKeys", dependencyKey, Map.of());
                }
                if (dependencyKey.equals(task.tempKey())) {
                    add(issues, "SELF_DEPENDENCY", "TASK", task.tempKey(),
                            "dependencyTempKeys", dependencyKey, Map.of());
                }
                PlanTask dependency = tasks.get(dependencyKey);
                if (dependency.dueDate() != null && task.startDate() != null
                        && dependency.dueDate().isAfter(task.startDate())) {
                    add(issues, "DEPENDENCY_DATE_CONFLICT", "TASK", task.tempKey(),
                            "startDate", dependency.tempKey(), Map.of(
                                    "dependencyDueDate", dependency.dueDate(),
                                    "currentStartDate", task.startDate()));
                }
            }
        }
        for (PlanTask task : tasks.values()) {
            if (isInCycle(task.tempKey(), tasks, new HashSet<>())) {
                add(issues, "DEPENDENCY_CYCLE", "TASK", task.tempKey(),
                        "dependencyTempKeys", null, Map.of());
            }
        }
        return new ValidationAssessment(issues);
    }

    public ValidationAssessment assess(ValidationContext context, TaskPlanDraft draft) {
        return assess(context, draft, ValidationMode.COMPLETE, false);
    }

    private static Map<String, Object> dateDetails(LocalDate start, LocalDate due) {
        Map<String, Object> details = new HashMap<>();
        if (start != null) details.put("startDate", start);
        if (due != null) details.put("dueDate", due);
        return Map.copyOf(details);
    }

    private static boolean isInCycle(String key, Map<String, PlanTask> tasks, Set<String> path) {
        if (!path.add(key)) return true;
        PlanTask task = tasks.get(key);
        if (task != null) {
            for (String dependency : task.dependencyTempKeys()) {
                if (tasks.containsKey(dependency) && isInCycle(dependency, tasks, new HashSet<>(path))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static StructuredValidationIssue issue(
            String code, ValidationIssueSeverity severity, String targetType,
            String targetTempKey, String field, String relatedTempKey,
            Map<String, Object> safeDetails) {
        return new StructuredValidationIssue(code, severity, targetType, targetTempKey,
                field, relatedTempKey, safeDetails);
    }

    private static void add(
            List<StructuredValidationIssue> issues,
            String code,
            String targetType,
            String targetTempKey,
            String field,
            String relatedTempKey,
            Map<String, Object> safeDetails) {
        issues.add(issue(code, ValidationIssueCatalog.severity(code), targetType,
                targetTempKey, field, relatedTempKey, safeDetails));
    }

    private static void validateLocatedSourceRefs(
            List<StructuredValidationIssue> issues,
            String targetType,
            String targetTempKey,
            List<String> refs,
            Set<String> validRefs) {
        Set<String> seen = new HashSet<>();
        for (String ref : refs) {
            if (ref == null || !ref.matches("^S([1-9]|1[0-2])$")) {
                add(issues, "SOURCE_REF_FORMAT_INVALID", targetType, targetTempKey,
                        "sourceRefs", null, ref == null ? Map.of() : Map.of("sourceRef", ref));
            } else if (!validRefs.contains(ref)) {
                add(issues, "SOURCE_REF_INVALID", targetType, targetTempKey,
                        "sourceRefs", null, Map.of("sourceRef", ref));
            }
            if (ref != null && !seen.add(ref)) {
                add(issues, "SOURCE_REF_DUPLICATE", targetType, targetTempKey,
                        "sourceRefs", ref, Map.of("sourceRef", ref));
            }
        }
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
