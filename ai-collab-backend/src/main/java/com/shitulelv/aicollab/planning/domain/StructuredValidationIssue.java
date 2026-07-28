package com.shitulelv.aicollab.planning.domain;

import java.util.Map;

/**
 * Task 2: Structured validation issue with full location and safe details.
 *
 * Replaces the flat error code list with precise targeting:
 * - code: stable issue identifier (e.g. DEPENDENCY_DATE_CONFLICT)
 * - severity: HARD / BLOCKING_EDITABLE / WARNING
 * - targetType: PLAN / MILESTONE / TASK
 * - targetTempKey: which milestone or task has the issue
 * - field: which field is affected (e.g. startDate, suggestedAssigneeId)
 * - relatedTempKey: related entity (e.g. prerequisite task for date conflict)
 * - safeDetails: whitelisted diagnostic info (dates, ranges, allowed enums, counts)
 *
 * safeDetails MUST NOT contain:
 * - user original goals
 * - document quotes
 * - member emails
 * - raw model output
 * - full exception messages
 * - SQL or stack traces
 */
public record StructuredValidationIssue(
        String code,
        ValidationIssueSeverity severity,
        String targetType,
        String targetTempKey,
        String field,
        String relatedTempKey,
        Map<String, Object> safeDetails
) {
    public StructuredValidationIssue {
        if (safeDetails == null) safeDetails = Map.of();
        safeDetails = Map.copyOf(safeDetails);
    }

    public boolean isHard() { return severity == ValidationIssueSeverity.HARD; }
    public boolean isBlockingEditable() { return severity == ValidationIssueSeverity.BLOCKING_EDITABLE; }
    public boolean isWarning() { return severity == ValidationIssueSeverity.WARNING; }
    public boolean blocksConfirmation() { return severity != ValidationIssueSeverity.WARNING; }
}
