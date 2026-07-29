package com.shitulelv.aicollab.project.application.view;

import java.util.List;

public record DashboardView(
        DashboardProjectView project,
        DashboardTaskStatsView tasks,
        List<DashboardMilestoneProgressView> milestones,
        List<DashboardRecentTaskView> recentTasks,
        List<DashboardRecentDocumentView> recentDocuments,
        List<DashboardActivityView> recentActivities) {
}
