package com.shitulelv.aicollab.home.application.service;

import com.shitulelv.aicollab.home.application.view.HomeView;
import com.shitulelv.aicollab.home.infrastructure.repository.HomeRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderView;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 个人工作台聚合：固定 6 次有界查询，不按项目循环。
 */
@Service
public class HomeApplicationService {
    private static final int RECENT_PROJECTS = 5;
    private static final int MY_TASKS = 8;
    private static final int RECENT_ACTIVITY = 8;

    private final HomeRepository home;
    private final NotificationApplicationService notifications;
    private final UserAiProviderService ai;

    public HomeApplicationService(
            HomeRepository home,
            NotificationApplicationService notifications,
            UserAiProviderService ai) {
        this.home = home;
        this.notifications = notifications;
        this.ai = ai;
    }

    public HomeView home(UUID userId) {
        List<UserAiProviderView> providers = ai.list(userId);
        String defaultModel = providers.stream()
                .filter(UserAiProviderView::isDefault)
                .map(UserAiProviderView::modelName)
                .findFirst()
                .orElse(null);
        return new HomeView(
                home.recentProjects(userId, RECENT_PROJECTS),
                home.myTasks(userId, MY_TASKS),
                new HomeView.NotificationsSummary(notifications.countUnread(userId)),
                home.pendingApprovals(userId),
                new HomeView.AiConfigSummary(!providers.isEmpty(), defaultModel, providers.size()),
                home.recentActivity(userId, RECENT_ACTIVITY));
    }
}
