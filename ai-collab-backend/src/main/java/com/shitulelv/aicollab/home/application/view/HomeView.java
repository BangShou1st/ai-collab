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

    /**
     * AI 配置摘要：只描述“是否保存了配置”和“是否存在可解析的已启用模型”，
     * 不代表真实调用成功，也不构成健康监控。{@code defaultModel} 仅在默认模型存在且已启用时给出。
     * {@code anyPurposeUsable} 表示知识问答/规划/Agent 中至少一个用途能按后端解析规则
     * （用途覆盖 → 已启用默认模型）解析到已启用配置；解析路径不以任何方式触发模型调用。
     */
    public record AiConfigSummary(
            boolean configured,
            String defaultModel,
            int providerCount,
            boolean anyPurposeUsable) {
    }
}
