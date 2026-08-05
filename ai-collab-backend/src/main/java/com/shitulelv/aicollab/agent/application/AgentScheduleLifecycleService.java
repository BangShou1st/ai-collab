package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.view.AgentScheduleView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentScheduleRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AgentScheduleLifecycleService {
    private final AgentScheduleRepository schedules;
    private final AuditService audit;

    public AgentScheduleLifecycleService(AgentScheduleRepository schedules, AuditService audit) {
        this.schedules = schedules;
        this.audit = audit;
    }

    @Transactional
    public void autoDisableForMissingCreator(AgentScheduleView schedule) {
        if (schedules.setEnabled(
                schedule.projectId(), schedule.id(), schedule.version(), false)) {
            audit.write(schedule.projectId(), schedule.creatorId(),
                    "AGENT_SCHEDULE_AUTO_DISABLED", "AGENT_SCHEDULE", schedule.id(),
                    Map.of("reason", "CREATOR_NOT_MEMBER"));
        }
    }
}
