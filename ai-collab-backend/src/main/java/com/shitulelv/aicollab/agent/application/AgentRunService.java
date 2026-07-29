package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.api.dto.CreateAgentSessionRequest;
import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.application.view.*;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.policy.AgentStateMachine;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
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
    private final AgentStateMachine states = new AgentStateMachine();

    public AgentRunService(ProjectAccessGuard access, AgentRepository repository) {
        this.access = access;
        this.repository = repository;
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
    public AgentSessionView getSession(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        return repository.findSession(projectId, sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    @Transactional
    public AgentRunView submit(
            UUID projectId, UUID sessionId, UUID userId, SubmitAgentMessageRequest request) {
        access.requireMember(projectId, userId);
        if (repository.findSession(projectId, sessionId).isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        }
        return repository.createRun(
                projectId, sessionId, userId, request.content().strip(), false);
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
        return new AgentRunDetailView(run, repository.listSteps(projectId, runId));
    }

    @Transactional
    public void cancel(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        AgentRunView run = repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        states.requireTransition(run.status(), AgentRunStatus.CANCELED);
        if (!repository.updateStatus(projectId, runId, run.version(),
                run.status(), AgentRunStatus.CANCELED, null)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
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
        return repository.findRun(projectId, runId).orElseThrow();
    }
}
