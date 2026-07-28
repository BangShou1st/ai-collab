package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
import com.shitulelv.aicollab.planning.domain.ValidationIssueSeverity;

import java.util.Map;
import java.util.UUID;

/** Safe API view that retains the persisted issue id required by scoped repair. */
public record TaskPlanValidationIssueView(
        UUID id,
        String code,
        ValidationIssueSeverity severity,
        String targetType,
        String targetTempKey,
        String field,
        String relatedTempKey,
        Map<String, Object> safeDetails
) {
    public static TaskPlanValidationIssueView from(
            com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository.PersistedIssue persisted) {
        StructuredValidationIssue issue = persisted.issue();
        return new TaskPlanValidationIssueView(
                persisted.id(), issue.code(), issue.severity(), issue.targetType(),
                issue.targetTempKey(), issue.field(), issue.relatedTempKey(), issue.safeDetails());
    }
}
