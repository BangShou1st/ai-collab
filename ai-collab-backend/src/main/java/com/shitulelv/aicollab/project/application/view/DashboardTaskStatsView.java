package com.shitulelv.aicollab.project.application.view;

public record DashboardTaskStatsView(
        int total,
        int todo,
        int inProgress,
        int blocked,
        int done,
        int canceled,
        int overdue,
        double completionRate) {
}
