package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskPlanQueryServiceTest {
    @Test
    void memberCanReadButReceivesNoWritePermissionsAndLookupStaysProjectScoped() {
        UUID project = UUID.randomUUID(), planId = UUID.randomUUID(), member = UUID.randomUUID();
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(access.requireMember(project, member)).thenReturn(ProjectRole.MEMBER);
        when(repository.require(project, planId)).thenReturn(plan(project, planId));
        lenient().when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        lenient().when(jdbc.queryForList(anyString(), (Object[]) any())).thenReturn(List.of());
        TaskPlanQueryService service = new TaskPlanQueryService(access, repository,
                mock(com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository.class),
                mock(com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRepository.class), jdbc,
                new TaskPlanActionPolicy());

        TaskPlanDetailView detail = service.detail(project, planId, member);

        assertThat(detail.permissions().canEdit()).isFalse();
        assertThat(detail.permissions().canCancel()).isFalse();
        assertThat(detail.permissions().canConfirm()).isFalse();
        verify(repository).require(project, planId);
        verify(access).requireMember(project, member);
    }

    // C2: Verify nullable fields don't cause NPE
    @Test
    void detailReturnsNullFieldsGracefullyForQueuedPlan() {
        UUID project = UUID.randomUUID(), planId = UUID.randomUUID(), member = UUID.randomUUID();
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(access.requireMember(project, member)).thenReturn(ProjectRole.ADMIN);
        when(repository.require(project, planId)).thenReturn(planWithStatus(project, planId,
                TaskPlanStatus.SKELETON_GENERATING, UUID.randomUUID()));
        lenient().when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        lenient().when(jdbc.queryForList(anyString(), (Object[]) any())).thenReturn(List.of());
        TaskPlanQueryService service = new TaskPlanQueryService(access, repository,
                mock(com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository.class),
                mock(com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRepository.class), jdbc,
                new TaskPlanActionPolicy());

        TaskPlanDetailView detail = service.detail(project, planId, member);

        // All nullable fields should be null, not throw NPE
        assertThat(detail.activeAttempt()).isNull();
        assertThat(detail.latestFailedAttempt()).isNull();
        assertThat(detail.latestAttempt()).isNull();
        assertThat(detail.confirmation()).isNull();
        assertThat(detail.latestVersion()).isNull();
        assertThat(detail.validation()).isNotNull();
        assertThat(detail.permissions().canEdit()).isFalse(); // not READY
        assertThat(detail.permissions().canCancel()).isTrue(); // generating
    }

    private static TaskPlanRecord plan(UUID project, UUID id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TaskPlanRecord(id, project, "Plan", "Goal", "",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 10, "[]",
                TaskPlanStatus.READY, 1, UUID.randomUUID(), 1, null, UUID.randomUUID(),
                null, null, now, now);
    }

    private static TaskPlanRecord planWithStatus(UUID project, UUID id, TaskPlanStatus status, UUID attemptId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TaskPlanRecord(id, project, "Plan", "Goal", "",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 10, "[]",
                status, 0, null, 1, attemptId, UUID.randomUUID(),
                null, null, now, now);
    }
}
