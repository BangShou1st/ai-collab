package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 验证 Agent 服务未启用（agent.enabled=false）时的防护边界：
 * 拒绝新任务提交、续跑恢复、重试与澄清继续，防止任务错误进入 QUEUED 队列却无消费者。
 */
class AgentRunDisabledGuardTest {
    private final ObjectMapper json = new ObjectMapper();
    private ProjectAccessGuard access;
    private AgentRepository repository;
    private AgentEventService events;
    private AgentHistoryVisibilityService visibility;
    private AgentRunService disabledService;

    private final UUID projectId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessGuard.class);
        repository = mock(AgentRepository.class);
        events = mock(AgentEventService.class);
        visibility = mock(AgentHistoryVisibilityService.class);
        // agentEnabled = false
        disabledService = new AgentRunService(access, repository, new AgentSkillRegistry(), json,
                events, mock(AgentEventRepository.class), mock(AgentApprovalRepository.class), visibility, false);
    }

    @Test
    void submitRejectsWhenAgentDisabled() {
        when(repository.findSession(projectId, sessionId)).thenReturn(Optional.of(mock(com.shitulelv.aicollab.agent.application.view.AgentSessionView.class)));
        when(repository.findLatestRun(projectId, sessionId)).thenReturn(Optional.empty());

        SubmitAgentMessageRequest request = new SubmitAgentMessageRequest("测试任务", null, null, null);

        assertThatThrownBy(() -> disabledService.submit(projectId, sessionId, userId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    org.assertj.core.api.Assertions.assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(be.getMessage()).contains("Agent 协作服务当前未开启");
                });

        verify(repository, never()).createRun(any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void resumeRejectsWhenAgentDisabled() {
        AgentRunView run = mock(AgentRunView.class);
        when(run.sessionId()).thenReturn(sessionId);
        when(repository.findRun(projectId, runId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> disabledService.resume(projectId, runId, userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    org.assertj.core.api.Assertions.assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                });

        verify(repository, never()).requestResume(any(), any());
    }

    @Test
    void retryRejectsWhenAgentDisabled() {
        AgentRunView run = mock(AgentRunView.class);
        when(run.sessionId()).thenReturn(sessionId);
        when(run.status()).thenReturn(AgentRunStatus.FAILED);
        when(repository.findRun(projectId, runId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> disabledService.retry(projectId, runId, userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    org.assertj.core.api.Assertions.assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                });

        verify(repository, never()).createRetryRun(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void continueRunRejectsWhenAgentDisabled() {
        AgentRunView run = mock(AgentRunView.class);
        when(run.sessionId()).thenReturn(sessionId);
        when(run.status()).thenReturn(AgentRunStatus.WAITING_FOR_USER_INPUT);
        when(repository.findRun(projectId, runId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> disabledService.continueRun(projectId, runId, userId, "继续回复"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    org.assertj.core.api.Assertions.assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                });

        verify(repository, never()).continueRun(any(), any());
    }
}
