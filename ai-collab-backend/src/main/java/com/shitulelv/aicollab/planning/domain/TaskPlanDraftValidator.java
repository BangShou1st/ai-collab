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
        Set<String> sourceRefValues = new HashSet<>();
        for (PlanSource source : draft.sources()) {
            if (source == null || blank(source.ref())) errors.add("SOURCE_INVALID");
            else if (!source.ref().matches("^S([1-9]|1[0-2])$")) errors.add("SOURCE_REF_FORMAT_INVALID");
            else {
                if (!sourceRefValues.add(source.ref())) errors.add("SOURCE_REF_DUPLICATE");
                sourceRefs.add(source.ref());
            }
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
     * Production structured validation entry point. Flat validation remains as a compatibility
     * view for older callers, while every returned issue is located by the domain validator
     * before it reaches persistence, repair, permissions or confirmation.
     */
    public ValidationAssessment assess(ValidationContext context, TaskPlanDraft draft,
                                       ValidationMode mode, boolean aiGenerated) {
        ValidationResult flat = validate(context, draft, mode, aiGenerated);
        List<StructuredValidationIssue> issues = new ArrayList<>();
        for (String code : flat.errorCodes()) {
            issues.addAll(locate(code, context, draft));
        }
        for (String code : flat.warningCodes()) {
            issues.addAll(locate(code, context, draft));
        }
        return new ValidationAssessment(issues);
    }

    public ValidationAssessment assess(ValidationContext context, TaskPlanDraft draft) {
        return assess(context, draft, ValidationMode.COMPLETE, false);
    }

    private List<StructuredValidationIssue> locate(String code, ValidationContext context, TaskPlanDraft draft) {
        ValidationIssueSeverity severity = ValidationIssueCatalog.severity(code);
        List<StructuredValidationIssue> located = new ArrayList<>();
        Map<String, PlanTask> tasks = new HashMap<>();
        for (PlanTask task : draft.tasks()) {
            if (task != null && task.tempKey() != null) tasks.put(task.tempKey(), task);
        }

        switch (code) {
            case "DEPENDENCY_DATE_CONFLICT" -> {
                for (PlanTask task : tasks.values()) {
                    for (String dependencyKey : task.dependencyTempKeys()) {
                        PlanTask dependency = tasks.get(dependencyKey);
                        if (dependency != null && dependency.dueDate() != null && task.startDate() != null
                                && dependency.dueDate().isAfter(task.startDate())) {
                            located.add(issue(code, severity, "TASK", task.tempKey(), "startDate",
                                    dependency.tempKey(), Map.of(
                                            "dependencyDueDate", dependency.dueDate(),
                                            "currentStartDate", task.startDate())));
                        }
                    }
                }
            }
            case "SELF_DEPENDENCY" -> {
                for (PlanTask task : tasks.values()) {
                    if (task.dependencyTempKeys().contains(task.tempKey())) {
                        located.add(issue(code, severity, "TASK", task.tempKey(),
                                "dependencyTempKeys", task.tempKey(), Map.of()));
                    }
                }
            }
            case "DEPENDENCY_DUPLICATE" -> {
                for (PlanTask task : tasks.values()) {
                    Set<String> seen = new HashSet<>();
                    for (String dependency : task.dependencyTempKeys()) {
                        if (dependency != null && !seen.add(dependency)) {
                            located.add(issue(code, severity, "TASK", task.tempKey(),
                                    "dependencyTempKeys", dependency, Map.of()));
                            break;
                        }
                    }
                }
            }
            case "DEPENDENCY_REF_INVALID" -> {
                for (PlanTask task : tasks.values()) {
                    for (String dependency : task.dependencyTempKeys()) {
                        if (dependency == null || !tasks.containsKey(dependency)) {
                            located.add(issue(code, severity, "TASK", task.tempKey(),
                                    "dependencyTempKeys", dependency, Map.of()));
                        }
                    }
                }
            }
            case "DEPENDENCY_CYCLE" -> {
                for (PlanTask task : tasks.values()) {
                    if (isInCycle(task.tempKey(), tasks, new HashSet<>())) {
                        located.add(issue(code, severity, "TASK", task.tempKey(),
                                "dependencyTempKeys", null, Map.of()));
                    }
                }
            }
            case "ASSIGNEE_NOT_PROJECT_MEMBER" -> {
                for (PlanTask task : tasks.values()) {
                    if (task.suggestedAssigneeId() != null
                            && !context.projectMemberIds().contains(task.suggestedAssigneeId())) {
                        located.add(issue(code, severity, "TASK", task.tempKey(),
                                "suggestedAssigneeId", null, Map.of()));
                    }
                    if (task.assigneeId() != null && !context.projectMemberIds().contains(task.assigneeId())) {
                        located.add(issue(code, severity, "TASK", task.tempKey(),
                                "assigneeId", null, Map.of()));
                    }
                }
            }
            case "SOURCE_REF_INVALID", "SOURCE_REF_FORMAT_INVALID", "SOURCE_REF_DUPLICATE",
                 "SOURCE_REF_LIMIT_EXCEEDED" -> locateSourceIssues(
                    code, severity, draft, located);
            case "TASK_DATE_INVALID" -> {
                for (PlanTask task : tasks.values()) {
                    if (outside(task.startDate(), context.planStartDate(), context.planDueDate())
                            || outside(task.dueDate(), context.planStartDate(), context.planDueDate())
                            || task.startDate() != null && task.dueDate() != null
                            && task.startDate().isAfter(task.dueDate())) {
                        located.add(issue(code, severity, "TASK", task.tempKey(),
                                outside(task.startDate(), context.planStartDate(), context.planDueDate())
                                        ? "startDate" : "dueDate", null,
                                dateDetails(task.startDate(), task.dueDate())));
                    }
                }
            }
            case "ESTIMATED_HOURS_INVALID" -> {
                for (PlanTask task : tasks.values()) {
                    if (task.estimatedHours() != null
                            && (task.estimatedHours().compareTo(BigDecimal.ZERO) <= 0
                            || task.estimatedHours().compareTo(BigDecimal.valueOf(80)) > 0)) {
                        located.add(issue(code, severity, "TASK", task.tempKey(), "estimatedHours",
                                null, Map.of("minimum", new BigDecimal("0.5"), "maximum", 80)));
                    }
                }
            }
            case "TASK_PRIORITY_INVALID" -> {
                for (PlanTask task : tasks.values()) {
                    if (task.priority() == null
                            || !Set.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(task.priority())) {
                        located.add(issue(code, severity, "TASK", task.tempKey(), "priority",
                                null, Map.of("allowedValues", List.of("LOW", "MEDIUM", "HIGH", "URGENT"))));
                    }
                }
            }
            case "MILESTONE_DATE_OUTSIDE_PLAN" -> {
                for (PlanMilestone milestone : draft.milestones()) {
                    if (milestone != null && outside(milestone.targetDate(),
                            context.planStartDate(), context.planDueDate())) {
                        located.add(issue(code, severity, "MILESTONE", milestone.tempKey(),
                                "targetDate", null, Map.of()));
                    }
                }
            }
            case "MILESTONE_REF_INVALID" -> {
                Set<String> milestoneKeys = new HashSet<>();
                for (PlanMilestone milestone : draft.milestones()) {
                    if (milestone != null) milestoneKeys.add(milestone.tempKey());
                }
                for (PlanTask task : tasks.values()) {
                    if (!milestoneKeys.contains(task.milestoneTempKey())) {
                        located.add(issue(code, severity, "TASK", task.tempKey(),
                                "milestoneTempKey", task.milestoneTempKey(), Map.of()));
                    }
                }
            }
            case "TASK_UNASSIGNED" -> {
                for (PlanTask task : tasks.values()) {
                    if (task.assigneeId() == null) {
                        located.add(issue(code, severity, "TASK", task.tempKey(),
                                "assigneeId", null, Map.of()));
                    }
                }
            }
            case "AI_SUGGESTION_WITHOUT_SOURCE" -> {
                for (PlanTask task : tasks.values()) {
                    if (task.sourceRefs().isEmpty()) {
                        located.add(issue(code, severity, "TASK", task.tempKey(),
                                "sourceRefs", null, Map.of()));
                    }
                }
            }
            default -> {
                String targetType = code.startsWith("TASK_") ? "TASK"
                        : code.startsWith("MILESTONE_") ? "MILESTONE" : "PLAN";
                located.add(issue(code, severity, targetType, null, fieldFor(code), null, Map.of()));
            }
        }
        if (located.isEmpty()) {
            located.add(issue(code, severity, "PLAN", null, fieldFor(code), null, Map.of()));
        }
        return located;
    }

    private static void locateSourceIssues(String code, ValidationIssueSeverity severity,
                                           TaskPlanDraft draft, List<StructuredValidationIssue> located) {
        Set<String> valid = new HashSet<>();
        for (PlanSource source : draft.sources()) {
            if (source != null && source.ref() != null) valid.add(source.ref());
        }
        for (PlanMilestone milestone : draft.milestones()) {
            if (milestone != null) {
                addSourceIssueIfNeeded(code, severity, "MILESTONE", milestone.tempKey(),
                        milestone.sourceRefs(), valid, located);
            }
        }
        for (PlanTask task : draft.tasks()) {
            if (task != null) {
                addSourceIssueIfNeeded(code, severity, "TASK", task.tempKey(),
                        task.sourceRefs(), valid, located);
            }
        }
    }

    private static void addSourceIssueIfNeeded(String code, ValidationIssueSeverity severity,
                                               String targetType, String targetTempKey,
                                               List<String> refs, Set<String> valid,
                                               List<StructuredValidationIssue> located) {
        Set<String> seen = new HashSet<>();
        for (String ref : refs) {
            boolean matches = ref != null && ref.matches("^S([1-9]|1[0-2])$");
            boolean invalid = switch (code) {
                case "SOURCE_REF_INVALID" -> matches && !valid.contains(ref);
                case "SOURCE_REF_FORMAT_INVALID" -> !matches;
                case "SOURCE_REF_DUPLICATE" -> ref != null && !seen.add(ref);
                case "SOURCE_REF_LIMIT_EXCEEDED" -> refs.size() > 5;
                default -> false;
            };
            if (invalid) {
                Map<String, Object> details = ref == null ? Map.of() : Map.of("sourceRef", ref);
                located.add(issue(code, severity, targetType, targetTempKey,
                        "sourceRefs", null, details));
                if (code.equals("SOURCE_REF_LIMIT_EXCEEDED")) return;
            }
            if (ref != null && !code.equals("SOURCE_REF_DUPLICATE")) seen.add(ref);
        }
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

    private static String fieldFor(String code) {
        return switch (code) {
            case "PLAN_DATE_OUTSIDE_PROJECT" -> "dateRange";
            case "SUMMARY_REQUIRED", "SUMMARY_TOO_LONG" -> "summary";
            case "ASSUMPTION_LIMIT_EXCEEDED" -> "assumptions";
            case "RISK_LIMIT_EXCEEDED" -> "risks";
            case "TASK_LIMIT_EXCEEDED", "TASK_REQUIRED", "TASK_NULL" -> "tasks";
            case "MILESTONE_LIMIT_EXCEEDED", "MILESTONE_REQUIRED", "MILESTONE_NULL" -> "milestones";
            case "SKELETON_MUTATED" -> "identity";
            default -> null;
        };
    }

    private static StructuredValidationIssue issue(
            String code, ValidationIssueSeverity severity, String targetType,
            String targetTempKey, String field, String relatedTempKey,
            Map<String, Object> safeDetails) {
        return new StructuredValidationIssue(code, severity, targetType, targetTempKey,
                field, relatedTempKey, safeDetails);
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
            // C8: Reject duplicate dependencies within a single task
            Set<String> seenDeps = new HashSet<>();
            for (String dependency : task.dependencyTempKeys()) {
                if (dependency == null) {
                    errors.add("DEPENDENCY_REF_INVALID");
                    continue;
                }
                if (!seenDeps.add(dependency)) {
                    errors.add("DEPENDENCY_DUPLICATE");
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
            if (ref == null || !ref.matches("^S([1-9]|1[0-2])$")) errors.add("SOURCE_REF_FORMAT_INVALID");
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
