package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentSessionView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * 验证基于系统动态配置中心的 Agent 实时启停拦截与放行能力：
 * 无须重启应用，动态由 disabled 变为 enabled 时即刻恢复服务能力。
 */
class AgentRunDynamicToggleTest {
    private final ObjectMapper json = new ObjectMapper();
    private ProjectAccessGuard access;
    private AgentRepository repository;
    private AgentEventService events;
    private AgentHistoryVisibilityService visibility;
    private SystemConfigService systemConfigService;
    private AgentRunService agentRunService;

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
        systemConfigService = mock(SystemConfigService.class);

        agentRunService = new AgentRunService(
                access, repository, new AgentSkillRegistry(), json,
                events, mock(AgentEventRepository.class), mock(AgentApprovalRepository.class),
                visibility, systemConfigService
        );
    }

    @Test
    void rejectsWhenDynamicallyDisabledAndAllowsWhenDynamicallyEnabled() {
        when(repository.findSession(projectId, sessionId)).thenReturn(Optional.of(mock(AgentSessionView.class)));
        when(repository.findLatestRun(projectId, sessionId)).thenReturn(Optional.empty());

        AgentRunView mockRun = mock(AgentRunView.class);
        when(mockRun.id()).thenReturn(runId);
        when(mockRun.sessionId()).thenReturn(sessionId);
        when(mockRun.status()).thenReturn(AgentRunStatus.QUEUED);
        when(repository.createRun(any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(mockRun);
        when(repository.findRun(projectId, runId)).thenReturn(Optional.of(mockRun));

        SubmitAgentMessageRequest request = new SubmitAgentMessageRequest("测试动态启停任务", null, null, null);

        // 阶段一：动态配置为关闭 (false)
        when(systemConfigService.isAgentEnabled()).thenReturn(false);

        assertThatThrownBy(() -> agentRunService.submit(projectId, sessionId, userId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                    assertThat(be.getMessage()).contains("Agent 协作服务当前未开启");
                });
        verify(repository, never()).createRun(any(), any(), any(), any(), anyBoolean(), any(), any());

        // 阶段二：系统管理员在后台动态开启 (true)
        when(systemConfigService.isAgentEnabled()).thenReturn(true);

        AgentRunView result = agentRunService.submit(projectId, sessionId, userId, request);
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(runId);
        verify(repository, times(1)).createRun(any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void resumeDynamicToggle() {
        AgentRunView run = mock(AgentRunView.class);
        when(run.sessionId()).thenReturn(sessionId);
        when(repository.findRun(projectId, runId)).thenReturn(Optional.of(run));
        when(repository.requestResume(projectId, runId)).thenReturn(run);

        // 初始为关闭
        when(systemConfigService.isAgentEnabled()).thenReturn(false);
        assertThatThrownBy(() -> agentRunService.resume(projectId, runId, userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));

        // 动态开启
        when(systemConfigService.isAgentEnabled()).thenReturn(true);
        var resumed = agentRunService.resume(projectId, runId, userId);
        assertThat(resumed).isNotNull();
        verify(repository, times(1)).requestResume(projectId, runId);
    }
}
