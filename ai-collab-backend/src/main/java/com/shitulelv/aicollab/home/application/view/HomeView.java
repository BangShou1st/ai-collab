package com.shitulelv.aicollab.home.application.view;

import java.util.List;

public record HomeView(
        List<HomeProjectView> recentProjects,
        List<HomeTaskView> myTasks,
        NotificationsSummary notificationsSummary,
        int pendingApprovals,
        AiConfigSummary aiConfigSummary,
        List<HomeActivityView> recentActivity) {

    public record NotificationsSummary(int unread) {
    }

    public record AiConfigSummary(boolean configured, String defaultModel, int providerCount) {
    }
}
