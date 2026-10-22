package com.shitulelv.aicollab.home.application.service;

import com.shitulelv.aicollab.home.application.view.HomeView;
import com.shitulelv.aicollab.home.infrastructure.repository.HomeRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderView;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
        Map<ModelPurpose, UUID> assignments = ai.listAssignments(userId);
        UserAiProviderView defaultProvider = providers.stream()
                .filter(UserAiProviderView::isDefault)
                .findFirst()
                .orElse(null);
        boolean defaultUsable = defaultProvider != null && defaultProvider.enabled();
        boolean anyPurposeUsable = Arrays.stream(ModelPurpose.values())
                .anyMatch(purpose -> usableForPurpose(providers, assignments, defaultProvider, purpose));
        return new HomeView(
                home.recentProjects(userId, RECENT_PROJECTS),
                home.myTasks(userId, MY_TASKS),
                new HomeView.NotificationsSummary(notifications.countUnread(userId)),
                home.pendingApprovals(userId),
                new HomeView.AiConfigSummary(!providers.isEmpty(),
                        defaultUsable ? defaultProvider.modelName() : null,
                        providers.size(), anyPurposeUsable),
                home.recentActivity(userId, RECENT_ACTIVITY));
    }

    /**
     * 与 {@code UserAiProviderService.resolve} 同一解析规则：用途覆盖（含已删除覆盖回退）→ 已启用默认模型。
     * 额外施加执行期才检查的“已启用”门槛，因此这里为 true 只表示“可解析到已启用配置”，
     * 既不触发模型调用，也不代表调用成功或服务健康。
     */
    private static boolean usableForPurpose(List<UserAiProviderView> providers, Map<ModelPurpose, UUID> assignments,
            UserAiProviderView defaultProvider, ModelPurpose purpose) {
        UUID assignedId = assignments.get(purpose);
        UserAiProviderView target = assignedId == null ? null
                : providers.stream().filter(provider -> provider.id().equals(assignedId)).findFirst().orElse(null);
        UserAiProviderView effective = target != null ? target : defaultProvider;
        return effective != null && effective.enabled();
    }
}
