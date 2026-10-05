package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AgentRunService 重试语义：终态（FAILED/CANCELED/BUDGET_EXCEEDED）派生新运行并保留旧记录，
 * FAILED_RETRYABLE 保持原运行转回 QUEUED 的运行内自动重试路径，非终态拒绝。
 */
class AgentRunServiceRetryTest {
    private final ObjectMapper json = new ObjectMapper();
    private ProjectAccessGuard access;
    private AgentRepository repository;
    private AgentEventService events;
    private AgentRunService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessGuard.class);
        repository = mock(AgentRepository.class);
        events = mock(AgentEventService.class);
        service = new AgentRunService(access, repository, new AgentSkillRegistry(), json,
                events, mock(AgentEventRepository.class), mock(AgentApprovalRepository.class));
    }

    @ParameterizedTest
    @EnumSource(value = AgentRunStatus.class, names = {"FAILED", "CANCELED", "BUDGET_EXCEEDED"})
    void retryFromTerminalStateDerivesNewRunKeepsOldRecordAndEmitsEvents(AgentRunStatus terminalStatus) {
        AgentRunView old = run(terminalStatus);
        AgentRunView derived = run(AgentRunStatus.QUEUED);
        when(repository.findRun(projectId, old.id())).thenReturn(Optional.of(old));
        when(repository.createRetryRun(eq(projectId), eq(old.sessionId()), eq(userId), eq(old.id()),
                eq(old.goal()), eq(old.skillCode()), eq(old.pageContextJson())))
                .thenReturn(new AgentRepository.RetryRunDerivation(derived, true));

        AgentRunView result = service.retry(projectId, old.id(), userId);

        assertThat(result.id()).isEqualTo(derived.id());
        assertThat(result.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(events).append(eq(projectId), eq(old.id()), eq(AgentEventType.RUN_RETRY_SCHEDULED),
                argThat(payload -> derived.id().toString().equals(payload.path("retriedToRunId").asText())));
        verify(events).append(eq(projectId), eq(derived.id()), eq(AgentEventType.RUN_CREATED),
                argThat(payload -> old.id().toString().equals(payload.path("retriedFromRunId").asText())));
        // 不放开状态转换：旧运行状态原地不动，也没有走 QUEUED 重入
        verify(repository, never()).updateStatus(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    /** 重复点击/并发重试命中幂等边界（created=false）：返回既有派生运行，不再重复发事件。 */
    @Test
    void repeatedRetryAfterDerivationReturnsExistingRunWithoutDuplicateEvents() {
        AgentRunView old = run(AgentRunStatus.FAILED);
        AgentRunView derived = run(AgentRunStatus.QUEUED);
        when(repository.findRun(projectId, old.id())).thenReturn(Optional.of(old));
        when(repository.createRetryRun(eq(projectId), eq(old.sessionId()), eq(userId), eq(old.id()),
                any(), any(), any()))
                .thenReturn(new AgentRepository.RetryRunDerivation(derived, false));

        AgentRunView result = service.retry(projectId, old.id(), userId);

        assertThat(result.id()).isEqualTo(derived.id());
        verifyNoInteractions(events);
    }

    @Test
    void retryableFailureStillRequeuesSameRun() {
        AgentRunView old = run(AgentRunStatus.FAILED_RETRYABLE);
        AgentRunView requeued = withStatus(old, AgentRunStatus.QUEUED);
        when(repository.findRun(projectId, old.id()))
                .thenReturn(Optional.of(old))
                .thenReturn(Optional.of(requeued));
        when(repository.updateStatus(projectId, old.id(), old.version(),
                AgentRunStatus.FAILED_RETRYABLE, AgentRunStatus.QUEUED, null)).thenReturn(true);

        AgentRunView result = service.retry(projectId, old.id(), userId);

        assertThat(result.id()).isEqualTo(old.id());
        assertThat(result.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(repository, never()).createRetryRun(any(), any(), any(), any(), any(), any(), any());
        verify(events).append(eq(projectId), eq(old.id()), eq(AgentEventType.RUN_RETRY_SCHEDULED), any());
    }

    /** SUCCEEDED 无任何出边：重试被状态机拒绝，且不产生派生或状态变更。 */
    @Test
    void retryFromSucceededIsRejectedWithoutSideEffects() {
        AgentRunView succeeded = run(AgentRunStatus.SUCCEEDED);
        when(repository.findRun(projectId, succeeded.id())).thenReturn(Optional.of(succeeded));

        assertThatThrownBy(() -> service.retry(projectId, succeeded.id(), userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUCCEEDED");
        verify(repository, never()).createRetryRun(any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).updateStatus(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    private AgentRunView withStatus(AgentRunView r, AgentRunStatus status) {
        return new AgentRunView(
                r.id(), r.sessionId(), r.projectId(), r.requesterId(),
                r.parentRunId(), r.role(), r.depth(), r.goal(), status,
                r.maxSteps(), r.maxToolCalls(), r.maxChildren(),
                r.maxInputTokens(), r.maxOutputTokens(),
                r.stepsUsed(), r.toolCallsUsed(), r.childrenUsed(),
                r.inputTokensUsed(), r.outputTokensUsed(), r.inputTokensActual(), r.outputTokensActual(),
                r.tokenUsageEstimated(), r.scheduled(), r.correctionAttempted(),
                r.retryCount(), r.errorCode(), r.planJson(), r.pageContextJson(), r.skillCode(),
                r.version(), r.createdAt(), r.updatedAt());
    }

    private AgentRunView run(AgentRunStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), projectId, userId,
                null, "SUPERVISOR", 0, "检查项目进度", status,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, 0, 0, false,
                false, false, 0, status == AgentRunStatus.FAILED ? "AI_PROVIDER_ERROR" : null,
                null, "{\"route\":\"TASK_BOARD\"}", "ITERATION_PLANNING",
                1, now, now);
    }
}
