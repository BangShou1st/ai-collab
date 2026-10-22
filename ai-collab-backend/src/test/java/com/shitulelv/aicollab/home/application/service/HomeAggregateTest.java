package com.shitulelv.aicollab.home.application.service;

import com.shitulelv.aicollab.home.application.view.HomeActivityView;
import com.shitulelv.aicollab.home.application.view.HomeProjectView;
import com.shitulelv.aicollab.home.application.view.HomeTaskView;
import com.shitulelv.aicollab.home.application.view.HomeView;
import com.shitulelv.aicollab.home.infrastructure.repository.HomeRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderView;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class HomeAggregateTest {

    private final UUID user = UUID.randomUUID();

    private static UserAiProviderView provider(UUID id, boolean enabled, boolean isDefault) {
        return new UserAiProviderView(id, "main", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.openai.com", "/v1/chat/completions", true, "gpt-4o-mini",
                enabled, isDefault, null, 0.2, 1200, Set.of(ModelCapability.CHAT), OffsetDateTime.now());
    }

    private static HomeRepository emptyRepository(UUID user) {
        HomeRepository repository = mock(HomeRepository.class);
        when(repository.recentProjects(user, 5)).thenReturn(List.of());
        when(repository.myTasks(user, 8)).thenReturn(List.of());
        when(repository.pendingApprovals(user)).thenReturn(0);
        when(repository.recentActivity(user, 8)).thenReturn(List.of());
        return repository;
    }

    private static UserAiProviderService aiWith(UUID user,
            List<UserAiProviderView> providers, Map<ModelPurpose, UUID> assignments) {
        UserAiProviderService ai = mock(UserAiProviderService.class);
        when(ai.list(user)).thenReturn(providers);
        when(ai.listAssignments(user)).thenReturn(assignments);
        return ai;
    }

    private HomeView aggregate(UserAiProviderService ai) {
        NotificationApplicationService notifications = mock(NotificationApplicationService.class);
        HomeApplicationService service = new HomeApplicationService(emptyRepository(user), notifications, ai);
        return service.home(user);
    }

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
        UserAiProviderService ai = aiWith(user, List.of(), new EnumMap<>(ModelPurpose.class));
        HomeApplicationService service = new HomeApplicationService(
                repository, notifications, ai);

        HomeView view = service.home(user);

        assertThat(view.recentProjects()).hasSize(1);
        assertThat(view.myTasks()).hasSize(1);
        assertThat(view.notificationsSummary().unread()).isEqualTo(3);
        assertThat(view.pendingApprovals()).isEqualTo(2);
        assertThat(view.aiConfigSummary().configured()).isFalse();
        assertThat(view.aiConfigSummary().anyPurposeUsable()).isFalse();
        assertThat(view.recentActivity()).hasSize(1);
        verify(repository, times(1)).recentProjects(user, 5);
        verify(repository, times(1)).myTasks(user, 8);
        verify(repository, times(1)).pendingApprovals(user);
        verify(repository, times(1)).recentActivity(user, 8);
        verify(notifications, times(1)).countUnread(user);
    }

    @Test
    void empty_home_for_new_user() {
        HomeApplicationService service = new HomeApplicationService(
                emptyRepository(user), mock(NotificationApplicationService.class),
                aiWith(user, List.of(), new EnumMap<>(ModelPurpose.class)));

        HomeView view = service.home(user);

        assertThat(view.recentProjects()).isEmpty();
        assertThat(view.myTasks()).isEmpty();
        assertThat(view.pendingApprovals()).isZero();
        assertThat(view.aiConfigSummary().configured()).isFalse();
        assertThat(view.aiConfigSummary().defaultModel()).isNull();
        assertThat(view.aiConfigSummary().providerCount()).isZero();
        assertThat(view.aiConfigSummary().anyPurposeUsable()).isFalse();
    }

    @Test
    void enabled_default_provider_makes_purposes_resolvable_but_not_verified() {
        UserAiProviderService ai = aiWith(user,
                List.of(provider(UUID.randomUUID(), true, true)), new EnumMap<>(ModelPurpose.class));

        HomeView.AiConfigSummary summary = aggregate(ai).aiConfigSummary();

        assertThat(summary.configured()).isTrue();
        assertThat(summary.providerCount()).isEqualTo(1);
        assertThat(summary.defaultModel()).isEqualTo("gpt-4o-mini");
        assertThat(summary.anyPurposeUsable()).isTrue();
    }

    @Test
    void disabled_default_provider_is_saved_but_not_resolvable() {
        UserAiProviderService ai = aiWith(user,
                List.of(provider(UUID.randomUUID(), false, true)), new EnumMap<>(ModelPurpose.class));

        HomeView.AiConfigSummary summary = aggregate(ai).aiConfigSummary();

        assertThat(summary.configured()).isTrue();
        assertThat(summary.defaultModel()).isNull();
        assertThat(summary.anyPurposeUsable()).isFalse();
    }

    @Test
    void saved_enabled_provider_without_default_is_not_resolvable() {
        UserAiProviderService ai = aiWith(user,
                List.of(provider(UUID.randomUUID(), true, false)), new EnumMap<>(ModelPurpose.class));

        HomeView.AiConfigSummary summary = aggregate(ai).aiConfigSummary();

        assertThat(summary.configured()).isTrue();
        assertThat(summary.defaultModel()).isNull();
        assertThat(summary.anyPurposeUsable()).isFalse();
    }

    @Test
    void purpose_override_resolves_when_default_is_disabled() {
        UserAiProviderView override = provider(UUID.randomUUID(), true, false);
        Map<ModelPurpose, UUID> assignments = new EnumMap<>(ModelPurpose.class);
        assignments.put(ModelPurpose.AGENT, override.id());
        UserAiProviderService ai = aiWith(user,
                List.of(provider(UUID.randomUUID(), false, true), override), assignments);

        HomeView.AiConfigSummary summary = aggregate(ai).aiConfigSummary();

        assertThat(summary.anyPurposeUsable()).isTrue();
        assertThat(summary.defaultModel()).isNull();
    }

    @Test
    void override_to_disabled_provider_beats_enabled_default() {
        UserAiProviderView disabled = provider(UUID.randomUUID(), false, false);
        Map<ModelPurpose, UUID> assignments = new EnumMap<>(ModelPurpose.class);
        assignments.put(ModelPurpose.KNOWLEDGE_CHAT, disabled.id());
        assignments.put(ModelPurpose.PLANNING, disabled.id());
        assignments.put(ModelPurpose.AGENT, disabled.id());
        UserAiProviderService ai = aiWith(user,
                List.of(provider(UUID.randomUUID(), true, true), disabled), assignments);

        HomeView.AiConfigSummary summary = aggregate(ai).aiConfigSummary();

        assertThat(summary.anyPurposeUsable()).isFalse();
        assertThat(summary.defaultModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void deleted_override_falls_back_to_enabled_default() {
        Map<ModelPurpose, UUID> assignments = new EnumMap<>(ModelPurpose.class);
        assignments.put(ModelPurpose.AGENT, UUID.randomUUID());
        UserAiProviderService ai = aiWith(user,
                List.of(provider(UUID.randomUUID(), true, true)), assignments);

        HomeView.AiConfigSummary summary = aggregate(ai).aiConfigSummary();

        assertThat(summary.anyPurposeUsable()).isTrue();
    }

    @Test
    void aggregation_never_reads_credentials_or_calls_models() {
        UserAiProviderService ai = aiWith(user,
                List.of(provider(UUID.randomUUID(), true, true)), new EnumMap<>(ModelPurpose.class));

        aggregate(ai);

        verify(ai, times(1)).list(user);
        verify(ai, times(1)).listAssignments(user);
        verifyNoMoreInteractions(ai);
    }
}
