package com.shitulelv.aicollab.planning.domain;

import java.util.Map;
import java.util.Set;

/**
 * Task 2: Single centralized source for issue severity, user message keys, and repairable fields.
 *
 * All severity decisions MUST go through this catalog.
 * Adding a new validation code requires adding it here first.
 */
public final class ValidationIssueCatalog {

    private static final Map<String, ValidationIssueSeverity> SEVERITY = Map.ofEntries(
            // ── HARD: structural / contract errors ──
            Map.entry("JSON_SYNTAX_INVALID", ValidationIssueSeverity.HARD),
            Map.entry("UNKNOWN_PROPERTY", ValidationIssueSeverity.HARD),
            Map.entry("MISSING_REQUIRED_FIELD", ValidationIssueSeverity.HARD),
            Map.entry("INVALID_FIELD_TYPE", ValidationIssueSeverity.HARD),
            Map.entry("TEMP_KEY_DUPLICATE", ValidationIssueSeverity.HARD),
            Map.entry("MILESTONE_REF_INVALID", ValidationIssueSeverity.HARD),
            Map.entry("SKELETON_MUTATED", ValidationIssueSeverity.HARD),
            Map.entry("DETAIL_DUPLICATE_MILESTONE_KEY", ValidationIssueSeverity.HARD),
            Map.entry("DETAIL_DUPLICATE_TASK_KEY", ValidationIssueSeverity.HARD),
            Map.entry("DETAIL_UNKNOWN_MILESTONE_KEY", ValidationIssueSeverity.HARD),
            Map.entry("DETAIL_UNKNOWN_TASK_KEY", ValidationIssueSeverity.HARD),
            Map.entry("DETAIL_MISSING_MILESTONE_KEY", ValidationIssueSeverity.HARD),
            Map.entry("DETAIL_MISSING_TASK_KEY", ValidationIssueSeverity.HARD),
            Map.entry("MILESTONE_LIMIT_EXCEEDED", ValidationIssueSeverity.HARD),
            Map.entry("MILESTONE_REQUIRED", ValidationIssueSeverity.HARD),
            Map.entry("TASK_LIMIT_EXCEEDED", ValidationIssueSeverity.HARD),
            Map.entry("TASK_REQUIRED", ValidationIssueSeverity.HARD),
            Map.entry("TASK_NULL", ValidationIssueSeverity.HARD),
            Map.entry("MILESTONE_NULL", ValidationIssueSeverity.HARD),
            Map.entry("SOURCE_LIMIT_EXCEEDED", ValidationIssueSeverity.HARD),
            Map.entry("SOURCE_INVALID", ValidationIssueSeverity.HARD),
            Map.entry("SOURCE_REF_FORMAT_INVALID", ValidationIssueSeverity.HARD),
            Map.entry("SOURCE_REF_DUPLICATE", ValidationIssueSeverity.HARD),
            Map.entry("PLAN_DATE_OUTSIDE_PROJECT", ValidationIssueSeverity.HARD),
            Map.entry("SUMMARY_REQUIRED", ValidationIssueSeverity.HARD),
            Map.entry("SUMMARY_TOO_LONG", ValidationIssueSeverity.HARD),
            Map.entry("ASSUMPTION_LIMIT_EXCEEDED", ValidationIssueSeverity.HARD),
            Map.entry("RISK_LIMIT_EXCEEDED", ValidationIssueSeverity.HARD),
            Map.entry("AI_GENERATED_ASSIGNEE_NOT_ALLOWED", ValidationIssueSeverity.HARD),
            Map.entry("MILESTONE_TEXT_INVALID", ValidationIssueSeverity.HARD),
            Map.entry("TASK_TEXT_INVALID", ValidationIssueSeverity.HARD),

            // ── BLOCKING_EDITABLE: business conflicts resolvable by editing ──
            Map.entry("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("DEPENDENCY_CYCLE", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("SELF_DEPENDENCY", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("DEPENDENCY_DUPLICATE", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("DEPENDENCY_REF_INVALID", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("DEPENDENCY_LIMIT_EXCEEDED", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("ASSIGNEE_NOT_PROJECT_MEMBER", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("SOURCE_REF_INVALID", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("SOURCE_REF_LIMIT_EXCEEDED", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("TASK_DATE_INVALID", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("MILESTONE_DATE_OUTSIDE_PLAN", ValidationIssueSeverity.BLOCKING_EDITABLE),
            Map.entry("ESTIMATED_HOURS_INVALID", ValidationIssueSeverity.BLOCKING_EDITABLE),
            // TASK_PRIORITY_INVALID is a schema contract violation — the model output an
            // enum value not in [LOW,MEDIUM,HIGH,URGENT]. Not user-editable; requires re-generation.
            Map.entry("TASK_PRIORITY_INVALID", ValidationIssueSeverity.HARD),
            Map.entry("SORT_ORDER_INVALID", ValidationIssueSeverity.BLOCKING_EDITABLE),

            // ── WARNING: non-blocking observations ──
            Map.entry("DUPLICATE_TITLE", ValidationIssueSeverity.WARNING),
            Map.entry("EXISTING_TITLE_SIMILAR", ValidationIssueSeverity.WARNING),
            Map.entry("TASK_UNASSIGNED", ValidationIssueSeverity.WARNING),
            Map.entry("AI_SUGGESTION_WITHOUT_SOURCE", ValidationIssueSeverity.WARNING)
    );

    private static final Map<String, Set<String>> REPAIRABLE_FIELDS = Map.ofEntries(
            Map.entry("DEPENDENCY_DATE_CONFLICT", Set.of("startDate", "dueDate")),
            Map.entry("DEPENDENCY_CYCLE", Set.of("dependencyTempKeys")),
            Map.entry("SELF_DEPENDENCY", Set.of("dependencyTempKeys")),
            Map.entry("DEPENDENCY_DUPLICATE", Set.of("dependencyTempKeys")),
            Map.entry("DEPENDENCY_REF_INVALID", Set.of("dependencyTempKeys")),
            Map.entry("DEPENDENCY_LIMIT_EXCEEDED", Set.of("dependencyTempKeys")),
            Map.entry("ASSIGNEE_NOT_PROJECT_MEMBER", Set.of("suggestedAssigneeId")),
            Map.entry("SOURCE_REF_INVALID", Set.of("sourceRefs")),
            Map.entry("SOURCE_REF_LIMIT_EXCEEDED", Set.of("sourceRefs")),
            Map.entry("TASK_DATE_INVALID", Set.of("startDate", "dueDate")),
            Map.entry("MILESTONE_DATE_OUTSIDE_PLAN", Set.of("targetDate")),
            Map.entry("ESTIMATED_HOURS_INVALID", Set.of("estimatedHours")),
            Map.entry("SORT_ORDER_INVALID", Set.of("sortOrder"))
    );

    private ValidationIssueCatalog() {}

    /** Returns the severity for a given issue code. Throws if code is unknown. */
    public static ValidationIssueSeverity severity(String code) {
        ValidationIssueSeverity s = SEVERITY.get(code);
        if (s == null) throw new IllegalArgumentException("Unknown validation issue code: " + code);
        return s;
    }

    /** Returns the severity, or WARNING for unknown codes (safe fallback). */
    public static ValidationIssueSeverity severityOrDefault(String code) {
        return SEVERITY.getOrDefault(code, ValidationIssueSeverity.WARNING);
    }

    /** Returns the set of fields that AI repair is allowed to modify for this code. */
    public static Set<String> repairableFields(String code) {
        return REPAIRABLE_FIELDS.getOrDefault(code, Set.of());
    }

    /** Returns true if the code is known to the catalog. */
    public static boolean isKnown(String code) {
        return SEVERITY.containsKey(code);
    }

    /** Returns true if the code blocks confirmation (HARD or BLOCKING_EDITABLE). */
    public static boolean blocksConfirmation(String code) {
        return severityOrDefault(code) != ValidationIssueSeverity.WARNING;
    }
}
