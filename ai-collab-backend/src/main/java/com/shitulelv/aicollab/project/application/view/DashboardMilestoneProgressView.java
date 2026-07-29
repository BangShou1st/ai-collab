package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.work.domain.model.MilestoneStatus;

import java.time.LocalDate;
import java.util.UUID;

public record DashboardMilestoneProgressView(
        UUID id,
        String name,
        MilestoneStatus status,
        LocalDate targetDate,
        int totalTasks,
        int completedTasks,
        double completionRate,
        boolean overdue) {
}
