package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;

import java.util.List;

/**
 * C2: Typed View for plan detail endpoint.
 * All nullable fields use explicit null instead of Map.of which rejects null values.
 * Task 11: Added structuredIssues and canPartialRegenerate permission.
 */
public record TaskPlanDetailView(
        TaskPlanRecord plan,
        TaskPlanVersionView latestVersion,
        TaskPlanAttemptView activeAttempt,
        TaskPlanAttemptView latestFailedAttempt,
        TaskPlanAttemptView latestAttempt,
        TaskPlanConfirmationView confirmation,
        TaskPlanValidationView validation,
        TaskPlanPermissions permissions,
        List<TaskPlanValidationIssueView> structuredIssues
) {
    public TaskPlanDetailView {
        structuredIssues = structuredIssues == null ? List.of() : List.copyOf(structuredIssues);
    }
}

record TaskPlanVersionView(
        String id,
        int versionNo,
        String sourceType,
        String basedOnVersionId,
        String createdBy,
        String createdAt
) {}

record TaskPlanAttemptView(
        String id,
        String stage,
        String status,
        String provider,
        String model,
        String startedAt,
        String finishedAt,
        Long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String errorCode,
        String errorSummary,
        String createdBy
) {}

record TaskPlanConfirmationView(
        String confirmationId,
        String status,
        List<String> milestoneIds,
        List<String> taskIds,
        Integer dependencyCount,
        String createdAt,
        String completedAt
) {}

record TaskPlanValidationView(
        List<String> errors,
        List<String> warnings
) {}

record TaskPlanPermissions(
        boolean canEdit,
        boolean canCancel,
        boolean canRetryDetail,
        boolean canRegenerate,
        boolean canConfirm,
        boolean canDelete,
        boolean canRestore,
        boolean canPartialRegenerate
) {}
