package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
        when(access.requireMember(project, member)).thenReturn(ProjectRole.MEMBER);
        when(repository.require(project, planId)).thenReturn(plan(project, planId));
        TaskPlanQueryService service = new TaskPlanQueryService(access, repository);

        Map<String, Object> detail = service.detail(project, planId, member);

        @SuppressWarnings("unchecked")
        Map<String, Boolean> permissions = (Map<String, Boolean>) detail.get("permissions");
        assertThat(permissions.values()).containsOnly(false);
        verify(repository).require(project, planId);
        verify(access).requireMember(project, member);
    }

    private static TaskPlanRecord plan(UUID project, UUID id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TaskPlanRecord(id, project, "Plan", "Goal", "",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 10, "[]",
                TaskPlanStatus.READY, 1, UUID.randomUUID(), 1, null, UUID.randomUUID(),
                null, null, now, now);
    }
}
