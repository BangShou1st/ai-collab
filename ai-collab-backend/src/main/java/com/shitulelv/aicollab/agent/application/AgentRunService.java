package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.api.dto.CreateAgentSessionRequest;
import com.shitulelv.aicollab.agent.api.dto.RenameAgentSessionRequest;
import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.api.dto.AgentPageContextRequest;
import com.shitulelv.aicollab.agent.application.view.*;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.policy.AgentStateMachine;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AgentRunService {
    private static final int MAX_SESSIONS = 100;
    private static final int MAX_MESSAGES = 500;
    private final ProjectAccessGuard access;
    private final AgentRepository repository;
    private final AgentSkillRegistry skillRegistry;
    private final AgentStateMachine states = new AgentStateMachine();
    private final ObjectMapper json;
    private final AgentEventService events;
    private final AgentEventRepository eventRepository;
    private final AgentApprovalRepository approvalRepository;

    public AgentRunService(ProjectAccessGuard access, AgentRepository repository,
                          AgentSkillRegistry skillRegistry, ObjectMapper json,
                          AgentEventService events, AgentEventRepository eventRepository,
                          AgentApprovalRepository approvalRepository) {
        this.access = access;
        this.repository = repository;
        this.skillRegistry = skillRegistry;
        this.json = json;
        this.events = events;
        this.eventRepository = eventRepository;
        this.approvalRepository = approvalRepository;
    }

    @Transactional
    public AgentSessionView createSession(
            UUID projectId, UUID userId, CreateAgentSessionRequest request) {
        access.requireMember(projectId, userId);
        return repository.createSession(projectId, userId, request.title().strip());
    }

    @Transactional(readOnly = true)
    public List<AgentSessionView> listSessions(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return repository.listSessions(projectId, MAX_SESSIONS);
    }

    @Transactional(readOnly = true)
    public List<AgentSessionSummaryView> listSessionSummaries(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return repository.listSessionSummaries(projectId, MAX_SESSIONS);
    }

    @Transactional(readOnly = true)
    public AgentRunDetailView latestRun(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        if (repository.findSession(projectId, sessionId).isEmpty())
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        return repository.findLatestRun(projectId, sessionId).map(run -> getRun(projectId, run.id(), userId)).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AgentApprovalView> runApprovals(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        repository.findRun(projectId, runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        return approvalRepository.listByRun(projectId, runId);
    }

    @Transactional(readOnly = true)
    public AgentSessionView getSession(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        return repository.findSession(projectId, sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    @Transactional
    public AgentSessionView renameSession(
            UUID projectId, UUID sessionId, UUID userId, RenameAgentSessionRequest request) {
        access.requireMember(projectId, userId);
        return repository.renameSession(projectId, sessionId, userId, request.title().strip())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    @Transactional
    public void deleteSession(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        if (!repository.deleteSession(projectId, sessionId, userId)) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        }
    }

    @Transactional
    public AgentRunView submit(
            UUID projectId, UUID sessionId, UUID userId, SubmitAgentMessageRequest request) {
        access.requireMember(projectId, userId);
        if (repository.findSession(projectId, sessionId).isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        }

        // 验证 skillCode
        String skillCode = request.skillCode();
        if (skillCode != null && !skillCode.isBlank()) {
            if (!skillRegistry.exists(skillCode.trim().toUpperCase())) {
                throw new BusinessException(ErrorCode.AGENT_SKILL_NOT_FOUND);
            }
            skillCode = skillCode.trim().toUpperCase();
        }

        // 转换 pageContext
        String pageContextJson = null;
        if (request.pageContext() != null) {
            AgentPageContext pageContext = new AgentPageContext(
                    request.pageContext().route(),
                    request.pageContext().selectedTaskId(),
                    request.pageContext().selectedMilestoneId(),
                    request.pageContext().selectedDocumentId(),
                    request.pageContext().selectedPlanId(),
                    request.pageContext().filters());
            try {
                pageContextJson = json.writeValueAsString(pageContext);
            } catch (Exception e) {
                pageContextJson = null;
            }
        }

        AgentRunView run = repository.createRun(
                projectId, sessionId, userId, request.content().strip(), false,
                skillCode, pageContextJson);
        events.append(projectId, run.id(), AgentEventType.RUN_CREATED,
                json.createObjectNode().put("status", run.status().name()));
        return run;
    }

    @Transactional(readOnly = true)
    public List<AgentMessageView> listMessages(
            UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        if (repository.findSession(projectId, sessionId).isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        }
        return repository.listMessages(projectId, sessionId, MAX_MESSAGES);
    }

    @Transactional(readOnly = true)
    public AgentRunDetailView getRun(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        AgentRunView run = repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        com.fasterxml.jackson.databind.JsonNode plan = null;
        try { if (run.planJson() != null) plan = json.readTree(run.planJson()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Agent 计划 JSON 无法读取", exception);
        }
        UUID pendingApproval = approvalRepository.list(projectId, "PENDING").stream()
                .filter(value -> value.runId().equals(runId)).map(AgentApprovalView::id).findFirst().orElse(null);
        return new AgentRunDetailView(run, plan, repository.listSteps(projectId, runId),
                eventRepository.lastSequence(projectId, runId), pendingApproval);
    }

    @Transactional
    public void cancel(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        AgentRunView run = repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        AgentRunStatus status = repository.requestCancel(projectId, runId);
        if (status == AgentRunStatus.CANCELED) {
            events.append(projectId, runId, AgentEventType.RUN_CANCELED,
                    json.createObjectNode().put("status", status.name()));
        }
    }

    @Transactional
    public AgentRunView retry(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        AgentRunView run = repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        states.requireTransition(run.status(), AgentRunStatus.QUEUED);
        if (!repository.updateStatus(projectId, runId, run.version(),
                run.status(), AgentRunStatus.QUEUED, null)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        AgentRunView retried = repository.findRun(projectId, runId).orElseThrow();
        events.append(projectId, runId, AgentEventType.RUN_RETRY_SCHEDULED,
                json.createObjectNode().put("status", retried.status().name()));
        return retried;
    }

    /**
     * 继续等待用户输入的 Run。
     */
    @Transactional
    public AgentRunView continueRun(UUID projectId, UUID runId, UUID userId, String userResponse) {
        access.requireMember(projectId, userId);
        AgentRunView run = repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));

        // 验证状态必须是 WAITING_FOR_USER_INPUT
        if (run.status() != AgentRunStatus.WAITING_FOR_USER_INPUT) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND,
                    "Agent 运行不在等待用户输入状态");
        }

        // 继续 Run
        AgentRunView continued = repository.continueRun(run, userResponse.strip());
        events.append(projectId, runId, AgentEventType.RUN_CREATED,
                json.createObjectNode().put("status", continued.status().name()));
        return continued;
    }
}
