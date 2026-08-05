package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.view.AgentScheduleView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentScheduleRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScheduleJobTest {
    @Test
    void disablesAndAuditsScheduleWhenCreatorIsNoLongerMember() {
        AgentScheduleRepository schedules = mock(AgentScheduleRepository.class);
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        NotificationApplicationService notifications = mock(NotificationApplicationService.class);
        AgentScheduleLifecycleService lifecycle = mock(AgentScheduleLifecycleService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);
        AgentScheduleView schedule = new AgentScheduleView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "weekly", "check project", "WEEKLY_REPORT", "WEEKLY", "Asia/Shanghai",
                LocalTime.of(9, 0), 3, true, OffsetDateTime.now(clock).minusMinutes(1),
                null, null, 4, OffsetDateTime.now(clock), OffsetDateTime.now(clock));
        when(schedules.due(OffsetDateTime.now(clock), 20)).thenReturn(List.of(schedule));
        doThrow(new BusinessException(ErrorCode.PROJECT_NOT_FOUND))
                .when(access).requireMember(schedule.projectId(), schedule.creatorId());

        new AgentScheduleJob(schedules, access, notifications, lifecycle, clock).tick();

        verify(lifecycle).autoDisableForMissingCreator(schedule);
    }
}
