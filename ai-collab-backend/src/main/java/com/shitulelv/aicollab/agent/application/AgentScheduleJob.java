package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.view.AgentScheduleView;
import com.shitulelv.aicollab.agent.domain.policy.AgentScheduleRule;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentScheduleRepository;
import com.shitulelv.aicollab.notification.application.service.NotificationApplicationService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.common.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Component
@ConditionalOnProperty(prefix = "agent", name = "enabled", havingValue = "true")
public class AgentScheduleJob {
    private final AgentScheduleRepository schedules;
    private final ProjectAccessGuard access;
    private final NotificationApplicationService notifications;
    private final AgentScheduleLifecycleService lifecycle;
    private final Clock clock;
    private final AgentScheduleRule rule = new AgentScheduleRule();

    public AgentScheduleJob(
            AgentScheduleRepository schedules, ProjectAccessGuard access,
            NotificationApplicationService notifications,
            AgentScheduleLifecycleService lifecycle, Clock clock) {
        this.schedules = schedules;
        this.access = access;
        this.notifications = notifications;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${agent.schedule-delay-ms:30000}")
    public void tick() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (AgentScheduleView schedule : schedules.due(now, 20)) {
            try {
                access.requireMember(schedule.projectId(), schedule.creatorId());
            } catch (BusinessException accessDenied) {
                lifecycle.autoDisableForMissingCreator(schedule);
                continue;
            }
            OffsetDateTime next = rule.next(
                    schedule.frequency(), ZoneId.of(schedule.timeZone()),
                    schedule.localTime(), schedule.weeklyDay(), schedule.nextFireAt());
            schedules.fire(schedule, next).ifPresent(run -> notifications.create(
                    schedule.projectId(), schedule.creatorId(), "AGENT_RUN_STARTED",
                        "项目协作 Agent 定时运行已开始", schedule.name(),
                    "AGENT_RUN", run.id()));
        }
    }

}
