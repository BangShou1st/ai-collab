package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * AgentWorker 原生路径已移至 AgentRuntimeCoordinator。
 * 此测试类只保留 Worker 层面的预算预检查。
 */
class AgentWorkerNativeTurnTest {

    @Test
    void stepBudgetExceededStopsBeforeCoordinator() {
        AgentRunView run = runWithUsage(12, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);

        AgentWorkerOutcome outcome = new AgentWorker(repository, coordinator)
                .process(claimed(run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(coordinator, never()).advance(any());
    }

    @Test
    void inputTokenBudgetExceededStopsBeforeCoordinator() {
        // v1 兼容路径：累计输入到限仍拦截下一次请求
        AgentRunView run = inputTokensExhausted();
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);

        AgentWorkerOutcome outcome = new AgentWorker(repository, coordinator)
                .process(claimed(run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(coordinator, never()).advance(any());
    }

    @Test
    void outputTokenBudgetExceededStopsBeforeCoordinator() {
        // v1 兼容路径：累计输出到限仍拦截下一次请求
        AgentRunView run = outputTokensExhausted();
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);

        AgentWorkerOutcome outcome = new AgentWorker(repository, coordinator)
                .process(claimed(run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(coordinator, never()).advance(any());
    }

    /**
     * v2 回归：累计输入/输出远超旧 50k/20k 上限（甚至超过已撤回的 8M 建议值）时，
     * Worker 前置检查<b>不</b>因累计 token 拦截下一次请求——只统计，不参与准入。
     */
    @Test
    void v2CumulativeTokensNeverBlockNextRequest() {
        AgentRunView run = v2WithHugeCumulativeUsage();
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.advance(run)).thenReturn(
                new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED, "完成", null, null));

        AgentWorkerOutcome outcome = new AgentWorker(repository, coordinator)
                .process(claimed(run));

        assertThat(outcome.status()).as("v2 不因累计 token 拒绝下一次请求")
                .isEqualTo(AgentRunStatus.SUCCEEDED);
        verify(coordinator).advance(run);
    }

    /** v2 运行：无累计上限（NULL），累计用量远超旧常量，但自身执行额度仍有余额。 */
    private AgentRunView v2WithHugeCumulativeUsage() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                64, 64, 3, null, null,
                3, 2, 0, 9_000_000, 5_000_000, 9_000_000L, 5_000_000L, false,
                false, false, 0, null, null, null, null, 1,
                com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy.V2, false, now, now);
    }

    @Test
    void withinBudgetDelegatesToCoordinator() {
        AgentRunView run = runWithUsage(0, 0);
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findRun(run.projectId(), run.id())).thenReturn(Optional.of(run));
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.advance(run)).thenReturn(
                new AgentWorkerOutcome(AgentRunStatus.SUCCEEDED, "完成", null, null));

        AgentWorkerOutcome outcome = new AgentWorker(repository, coordinator)
                .process(claimed(run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        verify(coordinator).advance(run);
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

    private AgentRunView inputTokensExhausted() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                12, 8, 3, 1000, 20_000,
                0, 0, 0, 1000, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }

    private AgentRunView outputTokensExhausted() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                12, 8, 3, 50_000, 100,
                0, 0, 0, 0, 100, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }

    private ClaimedAgentRun claimed(AgentRunView run) {
        return new ClaimedAgentRun(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                null, "SUPERVISOR", 0, run.goal(),
                AgentRunStatus.QUEUED, false, false, run.version());
    }
}
