package com.shitulelv.aicollab.planning.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Task 2: Aggregated validation result with severity-aware queries.
 *
 * Replaces the flat boolean valid() check with structured assessment:
 * - hasHardIssues(): any HARD severity issues → cannot be repaired, cannot proceed
 * - hasBlockingEditableIssues(): any BLOCKING_EDITABLE → can attempt repair or user edit
 * - ready(): no HARD and no BLOCKING_EDITABLE issues
 * - codes(): all issue codes for backward compatibility
 */
public record ValidationAssessment(List<StructuredValidationIssue> issues) {
    public ValidationAssessment {
        issues = List.copyOf(issues);
    }

    public boolean hasHardIssues() {
        return issues.stream().anyMatch(StructuredValidationIssue::isHard);
    }

    public boolean hasBlockingEditableIssues() {
        return issues.stream().anyMatch(StructuredValidationIssue::isBlockingEditable);
    }

    public boolean hasWarnings() {
        return issues.stream().anyMatch(StructuredValidationIssue::isWarning);
    }

    /** True when no HARD and no BLOCKING_EDITABLE issues exist — plan can proceed to READY or confirm. */
    public boolean ready() {
        return !hasHardIssues() && !hasBlockingEditableIssues();
    }

    /** True when there are only warnings — plan is READY but with observations. */
    public boolean readyWithWarnings() {
        return ready() && hasWarnings();
    }

    /** True when there are BLOCKING_EDITABLE issues but no HARD issues — can degrade to READY_WITH_ISSUES. */
    public boolean degradable() {
        return !hasHardIssues() && hasBlockingEditableIssues();
    }

    /** All issue codes, for backward compatibility with flat ValidationResult. */
    public List<String> codes() {
        return issues.stream().map(StructuredValidationIssue::code).collect(Collectors.toList());
    }

    /** All error codes (HARD + BLOCKING_EDITABLE), for backward compatibility. */
    public List<String> errorCodes() {
        return issues.stream()
                .filter(i -> i.severity() != ValidationIssueSeverity.WARNING)
                .map(StructuredValidationIssue::code)
                .collect(Collectors.toList());
    }

    /** All warning codes, for backward compatibility. */
    public List<String> warningCodes() {
        return issues.stream()
                .filter(StructuredValidationIssue::isWarning)
                .map(StructuredValidationIssue::code)
                .collect(Collectors.toList());
    }

    /** Convert to flat ValidationResult for backward compatibility. */
    public ValidationResult toFlat() {
        return new ValidationResult(errorCodes(), warningCodes());
    }

    /** Create empty assessment (no issues). */
    public static ValidationAssessment empty() {
        return new ValidationAssessment(List.of());
    }
}
