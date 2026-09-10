package com.shitulelv.aicollab.home.application.service;

import com.shitulelv.aicollab.home.application.view.HomeActivityView;
import com.shitulelv.aicollab.home.application.view.HomeProjectView;
import com.shitulelv.aicollab.home.application.view.HomeTaskView;
import com.shitulelv.aicollab.home.application.view.HomeView;
import com.shitulelv.aicollab.home.infrastructure.repository.HomeRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HomeAggregateTest {

    private final UUID user = UUID.randomUUID();

    @Test
    void aggregates_all_sections_with_bounded_queries() {
        HomeRepository repository = mock(HomeRepository.class);
        when(repository.recentProjects(user, 5)).thenReturn(List.of(
                new HomeProjectView(UUID.randomUUID(), "Alpha", "ACTIVE", "OWNER")));
        when(repository.myTasks(user, 8)).thenReturn(List.of(
                new HomeTaskView(UUID.randomUUID(), UUID.randomUUID(), "P", "Fix bug",
                        "IN_PROGRESS", "HIGH", null)));
        when(repository.pendingApprovals(user)).thenReturn(2);
        when(repository.recentActivity(user, 8)).thenReturn(List.of(
                new HomeActivityView(UUID.randomUUID(), UUID.randomUUID(), "P", "TASK_DONE", null)));
        NotificationApplicationService notifications = mock(NotificationApplicationService.class);
        when(notifications.countUnread(user)).thenReturn(3);
        UserAiProviderService ai = mock(UserAiProviderService.class);
        when(ai.list(user)).thenReturn(List.of());
        HomeApplicationService service = new HomeApplicationService(
                repository, notifications, ai);

        HomeView view = service.home(user);

        assertThat(view.recentProjects()).hasSize(1);
        assertThat(view.myTasks()).hasSize(1);
        assertThat(view.notificationsSummary().unread()).isEqualTo(3);
        assertThat(view.pendingApprovals()).isEqualTo(2);
        assertThat(view.aiConfigSummary().configured()).isFalse();
        assertThat(view.recentActivity()).hasSize(1);
        verify(repository, times(1)).recentProjects(user, 5);
        verify(repository, times(1)).myTasks(user, 8);
        verify(repository, times(1)).pendingApprovals(user);
        verify(repository, times(1)).recentActivity(user, 8);
        verify(notifications, times(1)).countUnread(user);
    }

    @Test
    void empty_home_for_new_user() {
        HomeRepository repository = mock(HomeRepository.class);
        when(repository.recentProjects(user, 5)).thenReturn(List.of());
        when(repository.myTasks(user, 8)).thenReturn(List.of());
        when(repository.pendingApprovals(user)).thenReturn(0);
        when(repository.recentActivity(user, 8)).thenReturn(List.of());
        NotificationApplicationService notifications = mock(NotificationApplicationService.class);
        when(notifications.countUnread(user)).thenReturn(0);
        UserAiProviderService ai = mock(UserAiProviderService.class);
        when(ai.list(user)).thenReturn(List.of());
        HomeApplicationService service = new HomeApplicationService(
                repository, notifications, ai);

        HomeView view = service.home(user);

        assertThat(view.recentProjects()).isEmpty();
        assertThat(view.myTasks()).isEmpty();
        assertThat(view.pendingApprovals()).isZero();
        assertThat(view.aiConfigSummary().configured()).isFalse();
        assertThat(view.aiConfigSummary().defaultModel()).isNull();
    }
}
