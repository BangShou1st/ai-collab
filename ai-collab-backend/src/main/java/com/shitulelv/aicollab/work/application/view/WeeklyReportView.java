package com.shitulelv.aicollab.work.application.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record WeeklyReportView(
        LocalDate reportDate,
        TaskStatistics taskStats,
        MilestoneStatistics milestoneStats,
        List<TopContributor> topContributors,
        List<String> highlights,
        List<String> risks
) {
    public record TaskStatistics(
            int totalTasks,
            int completedTasks,
            int inProgressTasks,
            int overdueTasks,
            BigDecimal completionRate
    ) {}

    public record MilestoneStatistics(
            int totalMilestones,
            int completedMilestones,
            int upcomingMilestones,
            int overdueMilestones
    ) {}

    public record TopContributor(
            String displayName,
            int completedTasks,
            int totalTasks
    ) {}
}
