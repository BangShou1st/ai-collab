package com.shitulelv.aicollab.work.application.view;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PlanComparisonView(
        UUID planId,
        String planName,
        List<TaskComparison> taskComparisons,
        ComparisonSummary summary
) {
    public record TaskComparison(
            String plannedTaskId,
            String plannedTitle,
            UUID actualTaskId,
            String actualTitle,
            String status,
            List<FieldDiff> diffs
    ) {}

    public record FieldDiff(
            String fieldName,
            String plannedValue,
            String actualValue
    ) {}

    public record ComparisonSummary(
            int totalPlanned,
            int matchedTasks,
            int modifiedTasks,
            int missingTasks,
            int extraTasks
    ) {}
}
