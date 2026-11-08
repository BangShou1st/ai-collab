package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.planning.application.TaskPlanGenerationOrchestrator;
import com.shitulelv.aicollab.planning.application.TaskPlanPartialRepairService;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.system.domain.model.SystemConfig;
import com.shitulelv.aicollab.system.infrastructure.repository.SystemConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2 动态调度行为验证：确定性等待真实调度次数与节奏边界，不依赖长 sleep。
 */
class AgentDynamicScheduleRegistrarTest {
    private static final long AWAIT_TIMEOUT_MS = 4_000;

    private SystemConfigRepository configRepository;
    private SystemConfigService configService;
    private AgentApprovalRepository approvals;
    private JdbcTemplate planningJdbc;
    private AgentPlanningOperationService planningOperations;
    private CountingExecutor workerExecutor;
    private final AtomicInteger workerDispatches = new AtomicInteger();
    private final AtomicInteger planningRecoveries = new AtomicInteger();
    private AgentDynamicScheduleRegistrar registrar;

    @BeforeEach
    void setUp() {
        configRepository = mock(SystemConfigRepository.class);
        configService = new SystemConfigService(
                configRepository, mock(com.shitulelv.aicollab.user.service.UserService.class),
                new ObjectMapper().findAndRegisterModules(), mock(JdbcTemplate.class), true);
        approvals = mock(AgentApprovalRepository.class);
        planningJdbc = mock(JdbcTemplate.class);
        planningOperations = mock(AgentPlanningOperationService.class);
        workerExecutor = new CountingExecutor();
    }

    @AfterEach
    void tearDown() {
        if (registrar != null) {
            registrar.stop();
            registrar = null;
        }
    }

    private void setConfig(boolean agentEnabled, long workerDelay, long scheduleDelay) {
        when(configRepository.findCurrent()).thenReturn(Optional.of(
                config(agentEnabled, workerDelay, scheduleDelay, 1L)));
        configService.init();
    }

    private SystemConfig config(boolean agentEnabled, long workerDelay, long scheduleDelay) {
        return config(agentEnabled, workerDelay, scheduleDelay, 1L);
    }

    private SystemConfig config(boolean agentEnabled, long workerDelay, long scheduleDelay, long version) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SystemConfig(SystemConfig.DEFAULT_ID, agentEnabled, true, 2,
                workerDelay, scheduleDelay, false, "", true, "", "", 0.55, 12, version, null, now, now);
    }

    private void startRegistrar() {
        AgentRuntimeJob runtimeJob = new AgentRuntimeJob(
                mock(AgentRecoveryJob.class), mock(AgentWorker.class), approvals, workerExecutor);
        ReflectionTestUtils.setField(runtimeJob, "systemConfigService", configService);

        AgentPlanningRecoveryJob planningJob = new AgentPlanningRecoveryJob(
                planningJdbc, mock(TaskPlanRepository.class), mock(TaskPlanGenerationOrchestrator.class),
                mock(TaskPlanPartialRepairService.class), planningOperations);
        ReflectionTestUtils.setField(planningJob, "systemConfigService", configService);
        ReflectionTestUtils.setField(planningJob, "enabled", true);

        when(planningJdbc.queryForList(anyString())).thenAnswer(invocation -> {
            planningRecoveries.incrementAndGet();
            return List.of();
        });

        registrar = new AgentDynamicScheduleRegistrar(
                new StubObjectProvider(runtimeJob), planningJob, configService);
        registrar.start();
    }

    private boolean awaitCount(AtomicInteger counter, int target) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT_MS * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (counter.get() >= target) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean awaitPlannerCount(int target) {
        return awaitCount(planningRecoveries, target);
    }

    @Test
    void a2_t01_t12_defaultCadenceFiresAndEachParameterDrivesItsOwnJob() {
        setConfig(true, 200L, 1000L);
        startRegistrar();

        assertThat(awaitCount(workerDispatches, 2)).isTrue();
        assertThat(awaitPlannerCount(1)).isTrue();
        assertThat(registrar.workerDelayMillis()).isEqualTo(200L);
        assertThat(registrar.scheduleDelayMillis()).isEqualTo(1000L);
    }

    @Test
    void a2_t02_increasingIntervalSlowsNextScheduling() {
        setConfig(true, 200L, 30000L);
        startRegistrar();
        assertThat(awaitCount(workerDispatches, 3)).isTrue();

        setConfig(true, 600L, 30000L);
        assertThat(awaitCount(workerDispatches, workerDispatches.get() + 2)).isTrue();

        long maxRecentGap = maxRecentGapMs(2);
        assertThat(maxRecentGap).as("调大间隔后最近两轮 tick 间隔应达到约 600ms").isGreaterThanOrEqualTo(450L);
    }

    @Test
    void a2_t03_decreasingIntervalSpeedsUpNextScheduling() {
        setConfig(true, 600L, 30000L);
        startRegistrar();
        assertThat(awaitCount(workerDispatches, 2)).isTrue();

        setConfig(true, 200L, 30000L);
        assertThat(awaitCount(workerDispatches, workerDispatches.get() + 2)).isTrue();

        long minRecentGap = minRecentGapMs(2);
        assertThat(minRecentGap).as("调小间隔后最近两轮 tick 间隔应回落到约 200ms").isLessThanOrEqualTo(450L);
    }

    @Test
    void a2_t05_repeatedConfigChangesDoNotDuplicateSchedules() {
        setConfig(true, 200L, 30000L);
        startRegistrar();
        assertThat(awaitCount(workerDispatches, 2)).isTrue();

        setConfig(true, 300L, 30000L);
        setConfig(true, 200L, 30000L);
        setConfig(true, 300L, 30000L);

        assertThat(awaitCount(workerDispatches, workerDispatches.get() + 1)).isTrue();
        int before = workerDispatches.get();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int ticksInWindow = workerDispatches.get() - before;

        assertThat(ticksInWindow)
                .as("多次修改后 1 秒窗口内的调度次数应与单一注册一致（200-300ms 间隔约 3-5 次）；"
                        + "若发生重复注册，计数会翻倍到 10 左右")
                .isBetween(2, 8);

        registrar.stop();
        assertThat(registrar.isRunning()).isFalse();
        int afterStop = workerDispatches.get();
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(workerDispatches.get() - afterStop)
                .as("stop 后不应再有新的 Worker 轮询派发（允许停止竞态中的最后一次）")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void a2_t06_restartLoadsPersistedConfigWithoutRestartSimulation() {
        setConfig(true, 400L, 30000L);
        startRegistrar();

        assertThat(awaitCount(workerDispatches, 2)).isTrue();

        long firstGap = maxRecentGapMs(1);
        assertThat(firstGap).as("重启后首个调度周期应按持久化的 400ms 间隔执行").isGreaterThanOrEqualTo(350L);
    }

    @Test
    void a2_t08_invalidDelaysAreClampedAndSchedulingContinues() {
        setConfig(true, 0L, -100L);
        startRegistrar();

        assertThat(registrar.workerDelayMillis()).isEqualTo(200L);
        assertThat(registrar.scheduleDelayMillis()).isEqualTo(1000L);
        assertThat(awaitCount(workerDispatches, 2)).isTrue();
    }

    @Test
    void a2_t07_agentDisabledStopsClaimingAndRecovering() {
        setConfig(false, 200L, 200L);
        startRegistrar();

        Mockito.verify(approvals, Mockito.timeout(AWAIT_TIMEOUT_MS).atLeast(2)).expirePending(Mockito.any());

        assertThat(workerDispatches.get()).as("Agent 禁用时不得派发 Worker 任务").isZero();
        assertThat(planningRecoveries.get()).as("Agent 禁用时不得触发规划恢复领取").isZero();
    }

    @Test
    void a2_t10_schedulingFollowsSyncedConfigAfterLostNotification() {
        setConfig(true, 200L, 30000L);
        startRegistrar();
        assertThat(awaitCount(workerDispatches, 2)).isTrue();

        when(configRepository.findCurrent()).thenReturn(Optional.of(config(true, 800L, 30000L, 2L)));
        configService.scheduledSync();

        assertThat(registrar.workerDelayMillis()).isEqualTo(800L);
        assertThat(awaitCount(workerDispatches, workerDispatches.get() + 2)).isTrue();
        assertThat(maxRecentGapMs(2)).isGreaterThanOrEqualTo(650L);
    }

    /**
     * M2 双轨制轮询降频：MQ 开启时兜底轮询下限抬到 fallbackPollMs（取大语义：
     * 管理员配置更快则收敛到下限，更慢则保持原值）；MQ 关闭时行为与降频前完全一致。
     */
    @Test
    void m2_mqEnabledFloorsFallbackPollInterval() {
        setConfig(true, 1000L, 30000L);
        startRegistrar();

        // MQ 总闸关闭（agentMqProperties 未注入）：tick 间隔保持管理员配置（1s）
        assertThat(registrar.workerDelayMillis()).isEqualTo(1000L);

        // MQ 开启：兜底轮询抬到 fallbackPollMs=5000（MQ 触发为主、轮询为兜底）
        ReflectionTestUtils.setField(registrar, "agentMqProperties",
                new com.shitulelv.aicollab.agent.infrastructure.mq.AgentMqProperties(
                        true, "127.0.0.1:1", "agent-run-queued", "p", "c",
                        java.time.Duration.ofSeconds(10), 5000L));
        assertThat(registrar.workerDelayMillis()).isEqualTo(5000L);

        // 管理员配置更慢（30s）时保持原值（取大而非覆盖）
        setConfig(true, 30000L, 30000L);
        assertThat(registrar.workerDelayMillis()).isEqualTo(30000L);
    }

    private long maxRecentGapMs(int window) {
        List<Long> dispatches = workerDispatchNanos;
        int size = dispatches.size();
        long max = 0;
        for (int i = Math.max(1, size - window); i < size; i++) {
            max = Math.max(max, (dispatches.get(i) - dispatches.get(i - 1)) / 1_000_000);
        }
        return max;
    }

    private long minRecentGapMs(int window) {
        List<Long> dispatches = workerDispatchNanos;
        int size = dispatches.size();
        long min = Long.MAX_VALUE;
        for (int i = Math.max(1, size - window); i < size; i++) {
            min = Math.min(min, (dispatches.get(i) - dispatches.get(i - 1)) / 1_000_000);
        }
        return min;
    }

    private final CopyOnWriteArrayList<Long> workerDispatchNanos = new CopyOnWriteArrayList<>();

    private final class CountingExecutor implements TaskExecutor {
        @Override
        public void execute(Runnable task) {
            workerDispatches.incrementAndGet();
            workerDispatchNanos.add(System.nanoTime());
        }
    }

    private record StubObjectProvider(AgentRuntimeJob job)
            implements org.springframework.beans.factory.ObjectProvider<AgentRuntimeJob> {
        @Override
        public AgentRuntimeJob getObject(Object... args) {
            return job;
        }

        @Override
        public AgentRuntimeJob getObject() {
            return job;
        }

        @Override
        public AgentRuntimeJob getIfAvailable() {
            return job;
        }

        @Override
        public AgentRuntimeJob getIfUnique() {
            return job;
        }
    }
}
