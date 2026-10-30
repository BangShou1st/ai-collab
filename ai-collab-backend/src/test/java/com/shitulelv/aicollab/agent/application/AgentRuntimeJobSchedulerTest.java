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

    /**
     * 有界等待"槽位释放后，下一次派发被线程池接收"，一旦成功立即停止，不多发 tick。
     *
     * <p>可靠条件就是 {@link RecordingExecutor#dispatchedCount()} 增长：它只在任务真正被
     * 线程池接收时递增，被 {@code AbortPolicy} 拒绝的 tick 不计入。上界由
     * {@link #TIMEOUT_SECONDS} 的墙钟时长约束（而非猜测的迭代次数），循环体只
     * {@link Thread#yield()} 让出 CPU，不使用 {@code Thread.sleep}。
     * 到点仍无增长即返回 {@code false}，用例随之失败——真实的槽位泄漏不会被掩盖。</p>
     */
    private static boolean awaitNextDispatch(RecordingExecutor recording, AgentRuntimeJob job) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            int before = recording.dispatchedCount();
            job.tick();
            if (recording.dispatchedCount() > before) {
                return true;
            }
            Thread.yield();
        }
        return false;
    }

    /**
     * 槽位释放后，后续<b>有限次数</b>的周期 tick 最终能领取第三个任务。
     *
     * <p>验证的是"释放最终生效"，而不是"完成信号后的第一次 tick 必须成功"：
     * {@code RecordingExecutor} 在池线程内 {@code countDown()}，此刻线程尚未回到零容量
     * 队列，紧随其后的 tick 被 {@code AbortPolicy} 正确拒绝属预期行为。
     * 因此这里在有界等待中反复 tick，一旦下一个任务被接收立即停止，不再多发 tick；
     * 条件判断是"派发计数增长"这一可靠信号，而非固定等待或单纯放大超时。</p>
     */
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
            assertThat(recording.dispatchedCount()).isEqualTo(2);

            firstGate.countDown();
            assertThat(recording.awaitTaskCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(awaitNextDispatch(recording, job))
                    .as("槽位释放后，有界等待内的周期 tick 应能再次派发")
                    .isTrue();
            assertThat(recording.dispatchedCount()).isEqualTo(3);

            assertThat(thirdClaimed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            verify(fixture.recovery, times(3)).claim(anyString(), any());
        } finally {
            secondGate.countDown();
        }
    }

    /**
     * Worker 抛异常时槽位不泄漏：失败后有限次数的周期 tick 最终能领取第二个任务。
     *
     * <p>与上一个用例同一类竞争窗口：失败 Runnable 的完成信号同样发生在池线程内，
     * 紧随其后的 tick 可能被正确拒绝，因此这里用同样"有界重试 + 达成即停"的方式验证。</p>
     */
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
        assertThat(recording.dispatchedCount()).isEqualTo(1);

        assertThat(awaitNextDispatch(recording, job))
                .as("Worker 失败后，有界等待内的周期 tick 应能再次派发（槽位未泄漏）")
                .isTrue();
        assertThat(recording.dispatchedCount()).isEqualTo(2);

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
                .withPropertyValues("agent.runtime-allowed=false")
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

    /**
     * 委托真实线程池，但在 Runnable 真正结束后计数，用于确定性地判断"槽位已释放"。
     *
     * <p>注意 {@code countDown()} 发生在包裹 Runnable 的 {@code finally} 里，也就是在
     * <b>池线程内部</b>：此时该线程还没回到零容量队列上阻塞，所以"完成信号已发出"只代表
     * 任务体结束，<b>不</b>代表线程池已能接收下一个任务。紧随其后的 tick 可能被正确拒绝
     * （{@code AbortPolicy}），这不是缺陷。因此调用方必须用"有界重试 + 达成即停"来验证，
     * 而不能假定信号后的第一次 tick 一定成功。</p>
     */
    private static final class RecordingExecutor implements TaskExecutor {
        private final TaskExecutor delegate;
        private final CountDownLatch completions;
        /** 真正被线程池接收（未被拒绝）的派发次数：作为"释放是否已生效"的可靠观测条件。 */
        private final AtomicInteger dispatches = new AtomicInteger();

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
            // 被拒绝的 tick 会抛出 TaskRejectedException，不计入派发。
            dispatches.incrementAndGet();
        }

        int dispatchedCount() {
            return dispatches.get();
        }

        boolean awaitTaskCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return completions.await(timeout, unit);
        }
    }
}
