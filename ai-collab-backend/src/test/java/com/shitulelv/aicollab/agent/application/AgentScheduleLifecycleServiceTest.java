package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.view.AgentScheduleView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentScheduleRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentScheduleLifecycleServiceTest {
    @Test
    void disableAndAuditShareOneTransactionalBoundary() throws Exception {
        AgentScheduleRepository schedules = mock(AgentScheduleRepository.class);
        AuditService audit = mock(AuditService.class);
        AgentScheduleView schedule = schedule();
        when(schedules.setEnabled(schedule.projectId(), schedule.id(), schedule.version(), false))
                .thenReturn(true);

        new AgentScheduleLifecycleService(schedules, audit).autoDisableForMissingCreator(schedule);

        verify(audit).write(schedule.projectId(), schedule.creatorId(),
                "AGENT_SCHEDULE_AUTO_DISABLED", "AGENT_SCHEDULE", schedule.id(),
                Map.of("reason", "CREATOR_NOT_MEMBER"));
        assertThat(AgentScheduleLifecycleService.class
                .getMethod("autoDisableForMissingCreator", AgentScheduleView.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    private AgentScheduleView schedule() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentScheduleView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "weekly", "check", "WEEKLY_REPORT", "WEEKLY", "Asia/Shanghai",
                LocalTime.of(9, 0), 3, true, now, null, null, 4, now, now);
    }
}
