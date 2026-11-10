package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.policy.AgentSessionBudgetPolicy;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentWorkerTest {

    @Test
    void exhaustedBudgetStopsBeforeCoordinator() {
        AgentRunView run = runWithUsage(12, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        AgentWorker worker = new AgentWorker(repository, coordinator);
        ClaimedAgentRun claimed = claimed(run);

        AgentWorkerOutcome outcome = worker.process(claimed);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_BUDGET_EXCEEDED");
        verify(coordinator, never()).advance(any());
    }

    @Test
    void exhaustedBudgetPublishesTerminalEvent() {
        AgentRunView run = runWithUsage(12, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        AgentEventService events = mock(AgentEventService.class);
        AgentWorker worker = new AgentWorker(repository, coordinator, events, new ObjectMapper());

        AgentWorkerOutcome outcome = worker.process(claimed(run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(events).append(eq(run.projectId()), eq(run.id()),
                eq(AgentEventType.RUN_BUDGET_EXCEEDED),
                argThat(payload -> "BUDGET_EXCEEDED".equals(payload.path("status").asText())
                        && "AGENT_BUDGET_EXCEEDED".equals(payload.path("errorCode").asText())));
    }

    @Test
    void coordinatorAdvanceDelegatesNormally() {
        AgentRunView run = runWithUsage(0, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.advance(run)).thenReturn(
                new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED, "完成", null, null));
        AgentWorker worker = new AgentWorker(repository, coordinator);

        AgentWorkerOutcome outcome = worker.process(claimed(run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo("完成");
        verify(coordinator).advance(run);
    }

    @Test
    void coordinatorCancellationReturnsCanceledStatus() {
        AgentRunView run = runWithUsage(0, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.advance(run)).thenThrow(new BusinessException(ErrorCode.AGENT_RUN_CANCELED));
        AgentWorker worker = new AgentWorker(repository, coordinator);

        AgentWorkerOutcome outcome = worker.process(claimed(run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(outcome.errorCode()).isEqualTo("RUN_CANCELLED");
        verify(repository).recordCanceled(run);
    }

    @Test
    void coordinatorBusinessExceptionPropagates() {
        AgentRunView run = runWithUsage(0, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.advance(run)).thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_ERROR));
        AgentWorker worker = new AgentWorker(repository, coordinator);

        assertThatThrownBy(() -> worker.process(claimed(run)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_ERROR));
    }

    @Test
    void runNotFoundThrowsBusinessException() {
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(any(), any())).thenReturn(Optional.empty());
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        AgentWorker worker = new AgentWorker(repository, coordinator);
        ClaimedAgentRun claimed = new ClaimedAgentRun(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "test",
                AgentRunStatus.QUEUED, false, false, 1);

        assertThatThrownBy(() -> worker.process(claimed))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_RUN_NOT_FOUND));
    }

    @Test
    void constructorRejectsNullCoordinator() {
        AgentRepository repository = mock(AgentRepository.class);
        assertThatThrownBy(() -> new AgentWorker(repository, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AgentRuntimeCoordinator");
    }

    // ========== 会话聚合预算准入（预算治理 v3，设计文档 §4.1） ==========

    @Test
    void sessionBudgetExhaustedIsPassedToCoordinatorAsLevel() {
        AgentRunView run = runWithUsage(1, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        when(repository.sessionAggregateBudget(run.sessionId()))
                .thenReturn(new AgentRepository.SessionAggregateBudget(5_000L, 5_000L));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.advance(run, AgentSessionBudgetPolicy.Level.EXHAUSTED)).thenReturn(
                new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED, "部分结果", null, null));
        AgentWorker worker = new AgentWorker(repository, coordinator);

        AgentWorkerOutcome outcome = worker.process(claimed(run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        // EXHAUSTED 走带分级的 advance 重载（强制无工具收尾由协调器消费），不走历史单参入口
        verify(coordinator).advance(run, AgentSessionBudgetPolicy.Level.EXHAUSTED);
        verify(coordinator, never()).advance(any());
    }

    @Test
    void sessionBudgetFinalizeIsPassedToCoordinatorAsLevel() {
        AgentRunView run = runWithUsage(1, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        when(repository.sessionAggregateBudget(run.sessionId()))
                .thenReturn(new AgentRepository.SessionAggregateBudget(100_000L, 90_000L));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.advance(run, AgentSessionBudgetPolicy.Level.FINALIZE)).thenReturn(
                new AgentWorkerOutcome(AgentRunStatus.QUEUED, null, null, null));
        AgentWorker worker = new AgentWorker(repository, coordinator);

        worker.process(claimed(run));

        verify(coordinator).advance(run, AgentSessionBudgetPolicy.Level.FINALIZE);
        verify(coordinator, never()).advance(any());
    }

    @Test
    void sessionBudgetNoneKeepsHistoricSingleArgAdvance() {
        AgentRunView run = runWithUsage(0, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        when(repository.sessionAggregateBudget(run.sessionId()))
                .thenReturn(new AgentRepository.SessionAggregateBudget(2_000_000L, 1_000L));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.advance(run)).thenReturn(
                new AgentWorkerOutcome(AgentRunStatus.QUEUED, null, null, null));
        AgentWorker worker = new AgentWorker(repository, coordinator);

        worker.process(claimed(run));

        verify(coordinator).advance(run);
    }

    @Test
    void savedResultRunSkipsSessionBudgetAdmission() {
        // 已保存结果（pendingModelTurn）的消费不消耗新一轮请求，不受会话准入影响——
        // 与既有预算判据同款写法，接管不得把有效最终文本丢成 BUDGET_EXCEEDED
        AgentRunView run = runWithUsage(1, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        when(repository.pendingModelTurn(run)).thenReturn(Optional.of(
                new com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult(
                        "已保存的回答", java.util.List.of(),
                        com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason.STOP,
                        null, "OPENAI_COMPATIBLE", "model-a", 10L)));
        when(repository.sessionAggregateBudget(run.sessionId()))
                .thenReturn(new AgentRepository.SessionAggregateBudget(1_000L, 1_000L));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.advance(run)).thenReturn(
                new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED, "已保存的回答", null, null));
        AgentWorker worker = new AgentWorker(repository, coordinator);

        AgentWorkerOutcome outcome = worker.process(claimed(run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        verify(coordinator).advance(run);
        verify(repository, never()).sessionAggregateBudget(any());
    }

    // ========== 辅助方法 ==========

    private AgentRunView runWithUsage(int steps, int tools) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                12, 8, 3, 50_000, 20_000,
                steps, tools, 0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }

    private ClaimedAgentRun claimed(AgentRunView run) {
        return new ClaimedAgentRun(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                null, "SUPERVISOR", 0, run.goal(),
                AgentRunStatus.QUEUED, false, false, run.version());
    }
}
