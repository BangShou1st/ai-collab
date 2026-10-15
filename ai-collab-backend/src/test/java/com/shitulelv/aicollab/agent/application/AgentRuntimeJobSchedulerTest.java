package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 调度隔离的确定性并发测试。
 *
 * <p>用 Mockito + CountDownLatch + 有界超时同步，不依赖真实模型，也不使用 Thread.sleep。
 * 所有"槽位已释放"的等待都通过 {@link RecordingExecutor} 观察 Runnable 真实结束，
 * 从而避免依赖线程池内部状态的时序猜测。</p>
 */
class AgentRuntimeJobSchedulerTest {
    private static final int TIMEOUT_SECONDS = 5;

    private final List<ThreadPoolTaskExecutor> pools = new ArrayList<>();

    @AfterEach
    void shutdownPools() {
        pools.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    @Test
    void schedulerThreadEndsTickWithoutWaitingForRunningWorker() throws Exception {
        AgentRecoveryJob recovery = mock(AgentRecoveryJob.class);
        AgentWorker worker = mock(AgentWorker.class);
        AgentApprovalRepository approvals = mock(AgentApprovalRepository.class);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        when(recovery.claim(anyString(), any())).thenReturn(Optional.of(claimedRun()));
        when(worker.process(any())).thenAnswer(invocation -> {
            workerEntered.countDown();
            releaseWorker.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return null;
        });
        AgentRuntimeJob job = new AgentRuntimeJob(recovery, worker, approvals, newPool(2));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> tick = caller.submit(job::tick);

            assertThat(tick.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
            assertThat(workerEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            // Worker 仍在阻塞等待模型响应，说明调度线程没有同步执行它。
            assertThat(releaseWorker.getCount()).isEqualTo(1);
            verify(approvals).expirePending(any(OffsetDateTime.class));
        } finally {
            releaseWorker.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void twoClaimsEnterProcessingIndependently() throws Exception {
        Fixture fixture = new Fixture(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.worker.process(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return null;
        });

        try {
            fixture.job.tick();
            fixture.job.tick();

            assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(fixture.recovery, times(2)).claim(anyString(), any());
        } finally {
            release.countDown();
        }
    }

    @Test
    void saturatedExecutorSkipsClaimOnNextTick() throws Exception {
        Fixture fixture = new Fixture(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.worker.process(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return null;
        });

        try {
            fixture.job.tick();
            fixture.job.tick();
            assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            fixture.job.tick();

            // 两个槽位已占满：第三个 tick 不得再领取 Run。
            verify(fixture.recovery, times(2)).claim(anyString(), any());
            // 调度线程本身仍完成了短事务。
            verify(fixture.approvals, times(3)).expirePending(any(OffsetDateTime.class));
        } finally {
            release.countDown();
        }
    }

    @Test
    void rejectedDispatchDoesNotCreateOrphanRunningRun() throws Exception {
        Fixture fixture = new Fixture(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.worker.process(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return null;
        });

        try {
            fixture.job.tick();
            fixture.job.tick();
            assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThatCode(fixture.job::tick).doesNotThrowAnyException();

            // 被拒绝的 tick 不领取任务，因此不会产生已领取但无人处理的 RUNNING 记录。
            verify(fixture.recovery, times(2)).claim(anyString(), any());
            assertThat(fixture.delegate().getActiveCount()).isEqualTo(2);
        } finally {
            release.countDown();
        }
    }

    @Test
    void releasingOneSlotAllowsNextTickToClaimAgain() throws Exception {
        Fixture fixture = new Fixture(2);
        AtomicInteger invocation = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch firstGate = new CountDownLatch(1);
        CountDownLatch secondGate = new CountDownLatch(1);
        CountDownLatch thirdClaimed = new CountDownLatch(1);
        when(fixture.worker.process(any())).thenAnswer(inv -> {
            int index = invocation.incrementAndGet();
            entered.countDown();
            if (index == 3) {
                thirdClaimed.countDown();
                return null;
            }
            (index == 1 ? firstGate : secondGate).await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return null;
        });
        RecordingExecutor recording = new RecordingExecutor(newPool(2), 1);
        AgentRuntimeJob job = new AgentRuntimeJob(
                fixture.recovery, fixture.worker, fixture.approvals, recording);

        try {
            job.tick();
            job.tick();
            assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(fixture.recovery, times(2)).claim(anyString(), any());

            firstGate.countDown();
            assertThat(recording.awaitTaskCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            job.tick();
            assertThat(thirdClaimed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            verify(fixture.recovery, times(3)).claim(anyString(), any());
        } finally {
            secondGate.countDown();
        }
    }

    @Test
    void workerFailureDoesNotLeakAnExecutionSlot() throws Exception {
        Fixture fixture = new Fixture(2);
        AtomicInteger invocation = new AtomicInteger();
        CountDownLatch secondEntered = new CountDownLatch(1);
        when(fixture.worker.process(any())).thenAnswer(inv -> {
            if (invocation.incrementAndGet() == 1) {
                throw new IllegalStateException("模型调用失败");
            }
            secondEntered.countDown();
            return null;
        });
        RecordingExecutor recording = new RecordingExecutor(newPool(2), 1);
        AgentRuntimeJob job = new AgentRuntimeJob(
                fixture.recovery, fixture.worker, fixture.approvals, recording);

        job.tick();
        assertThat(recording.awaitTaskCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        job.tick();

        assertThat(secondEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        verify(fixture.recovery, times(2)).claim(anyString(), any());
    }

    @Test
    void contextCreatesBoundedExecutorWhenAgentEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentRunExecutionConfiguration.class)
                .withPropertyValues("agent.enabled=true", "agent.worker-concurrency=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor executor =
                            context.getBean("agentRunTaskExecutor", ThreadPoolTaskExecutor.class);
                    // Spring 生命周期已完成初始化，可直接读取真实线程池配置。
                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(3);
                    assertThat(executor.getQueueCapacity()).isZero();
                    assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                            .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
                });
    }

    @Test
    void contextFailsToStartWhenConcurrencyOutOfRange() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentRunExecutionConfiguration.class)
                .withPropertyValues("agent.enabled=true", "agent.worker-concurrency=9")
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner()
                .withUserConfiguration(AgentRunExecutionConfiguration.class)
                .withPropertyValues("agent.enabled=true", "agent.worker-concurrency=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextOmitsExecutorWhenAgentDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentRunExecutionConfiguration.class)
                .withPropertyValues("agent.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("agentRunTaskExecutor");
                });
    }

    private ThreadPoolTaskExecutor newPool(int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("agent-run-test-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        pools.add(executor);
        return executor;
    }

    private static ClaimedAgentRun claimedRun() {
        return new ClaimedAgentRun(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.QUEUED, false, false, 1);
    }

    private final class Fixture {
        final AgentRecoveryJob recovery = mock(AgentRecoveryJob.class);
        final AgentWorker worker = mock(AgentWorker.class);
        final AgentApprovalRepository approvals = mock(AgentApprovalRepository.class);
        final ThreadPoolTaskExecutor pool;
        final AgentRuntimeJob job;

        Fixture(int concurrency) {
            pool = newPool(concurrency);
            when(recovery.claim(anyString(), any())).thenReturn(Optional.of(claimedRun()));
            job = new AgentRuntimeJob(recovery, worker, approvals, pool);
        }

        ThreadPoolExecutor delegate() {
            return pool.getThreadPoolExecutor();
        }
    }

    /** 委托真实线程池，但在 Runnable 真正结束后计数，用于确定性地判断"槽位已释放"。 */
    private static final class RecordingExecutor implements TaskExecutor {
        private final TaskExecutor delegate;
        private final CountDownLatch completions;

        RecordingExecutor(TaskExecutor delegate, int expectedCompletions) {
            this.delegate = delegate;
            this.completions = new CountDownLatch(expectedCompletions);
        }

        @Override
        public void execute(Runnable task) {
            delegate.execute(() -> {
                try {
                    task.run();
                } finally {
                    completions.countDown();
                }
            });
        }

        boolean awaitTaskCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return completions.await(timeout, unit);
        }
    }
}
