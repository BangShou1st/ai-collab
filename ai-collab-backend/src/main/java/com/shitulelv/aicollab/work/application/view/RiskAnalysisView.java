package com.shitulelv.aicollab.work.application.view;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RiskAnalysisView(
        List<RiskItem> risks,
        RiskSummary summary
) {
    public record RiskItem(
            UUID taskId,
            String taskTitle,
            String riskType,
            String severity,
            String description,
            LocalDate dueDate,
            String assigneeName
    ) {}

    public record RiskSummary(
            int highRiskCount,
            int mediumRiskCount,
            int lowRiskCount,
            String overallAssessment
    ) {}
}
