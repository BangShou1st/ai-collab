package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentWorker;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 委派唤醒监听器单测（§3.4）：唤醒是优化非正确性依赖——监听器必须吞掉一切异常
 * （执行器拒绝、claim/process 失败都不得外溢），领到运行即交给 worker 处理。
 */
class AgentImmediateWakeListenerTest {

    @SuppressWarnings("unchecked")
    private AgentImmediateWakeListener listener(
            AgentRecoveryJob recovery, java.util.function.Consumer<Runnable> executorHook) {
        // TaskExecutor 用最小实现承接：执行钩子决定同步执行/拒绝/抛异常
        org.springframework.core.task.TaskExecutor executor = task -> {
            if (executorHook != null) executorHook.accept(task);
            else task.run();
        };
        return new AgentImmediateWakeListener(recovery, mock(AgentWorker.class), executor);
    }

    private ClaimedAgentRun claimed(UUID runId) {
        return new ClaimedAgentRun(runId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "goal", com.shitulelv.aicollab.agent.domain.model.AgentRunStatus.QUEUED,
                false, false, 1);
    }

    @Test
    void wakeSubmitsSingleClaimAttemptAndProcessesClaimedRun() {
        UUID parentRunId = UUID.randomUUID();
        AgentRecoveryJob recovery = mock(AgentRecoveryJob.class);
        when(recovery.claim(any(), any())).thenReturn(Optional.of(claimed(parentRunId)));
        AgentWorker worker = mock(AgentWorker.class);
        var executed = new CopyOnWriteArrayList<Runnable>();
        AgentImmediateWakeListener listener = new AgentImmediateWakeListener(recovery, worker,
                executed::add);
        listener.wake(parentRunId);
        assertThat(executed).hasSize(1);
        executed.getFirst().run();
        org.mockito.Mockito.verify(recovery).claim(any(), any());
        org.mockito.Mockito.verify(worker).process(any());
    }

    @Test
    void taskRejectionIsSwallowed() {
        AgentRecoveryJob recovery = mock(AgentRecoveryJob.class);
        AgentImmediateWakeListener listener = listener(recovery, task -> {
            throw new TaskRejectedException("full");
        });
        assertThatCode(() -> listener.wake(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void claimFailureInsideTaskIsSwallowed() {
        AgentRecoveryJob recovery = mock(AgentRecoveryJob.class);
        when(recovery.claim(any(), any())).thenThrow(new IllegalStateException("claim failed"));
        AgentImmediateWakeListener listener = listener(recovery, null);
        assertThatCode(() -> listener.wake(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void eventEntryDelegatesToWake() {
        AgentRecoveryJob recovery = mock(AgentRecoveryJob.class);
        when(recovery.claim(any(), any())).thenReturn(Optional.empty());
        var executed = new CopyOnWriteArrayList<Runnable>();
        AgentImmediateWakeListener listener = new AgentImmediateWakeListener(recovery,
                mock(AgentWorker.class), executed::add);
        assertThatCode(() -> listener.onDelegationWake(new AgentDelegationWakeEvent(UUID.randomUUID())))
                .doesNotThrowAnyException();
        assertThat(executed).hasSize(1);
    }
}
