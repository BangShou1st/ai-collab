package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.api.dto.CreateAgentScheduleRequest;
import com.shitulelv.aicollab.agent.application.view.AgentScheduleView;
import com.shitulelv.aicollab.agent.domain.policy.AgentScheduleRule;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentScheduleRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class AgentScheduleService {
    private final AgentScheduleRepository schedules;
    private final ProjectAccessGuard access;
    private final Clock clock;
    private final AgentRepository runs;
    private final AgentScheduleRule rule = new AgentScheduleRule();

    public AgentScheduleService(
            AgentScheduleRepository schedules, ProjectAccessGuard access,
            AgentRepository runs, Clock clock) {
        this.schedules = schedules;
        this.access = access;
        this.runs = runs;
        this.clock = clock;
    }

    public List<AgentScheduleView> list(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return schedules.list(projectId);
    }

    public AgentScheduleView create(
            UUID projectId, UUID userId, CreateAgentScheduleRequest request) {
        access.requireAdmin(projectId, userId);
        if (runs.findSession(projectId, request.sessionId()).isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        }
        ZoneId zone;
        try { zone = ZoneId.of(request.timeZone()); }
        catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "无效时区");
        }
        if ("DAILY".equals(request.frequency()) && request.weeklyDay() != null
                || "WEEKLY".equals(request.frequency()) && request.weeklyDay() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "weeklyDay 与 frequency 不匹配");
        }
        OffsetDateTime next = rule.next(
                request.frequency(), zone, request.localTime(), request.weeklyDay(),
                OffsetDateTime.now(clock));
        return schedules.create(
                projectId, userId, request.sessionId(), request.name().trim(),
                request.goal().trim(), request.frequency(), zone.getId(),
                request.localTime(), request.weeklyDay(), next);
    }

    public AgentScheduleView setEnabled(
            UUID projectId, UUID scheduleId, UUID userId, int version, boolean enabled) {
        access.requireAdmin(projectId, userId);
        if (!schedules.setEnabled(projectId, scheduleId, version, enabled)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        return schedules.find(projectId, scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SCHEDULE_NOT_FOUND));
    }
}
