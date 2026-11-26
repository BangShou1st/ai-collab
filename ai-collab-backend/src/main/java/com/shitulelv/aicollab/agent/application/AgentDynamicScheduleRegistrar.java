package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.system.domain.model.SystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Agent 动态调度注册器。
 *
 * <p>替代 {@code AgentRuntimeJob}/{@code AgentPlanningRecoveryJob} 原先的静态
 * {@code @Scheduled(fixedDelayString=...)}：在容器启动完成后把两个 tick 任务
 * 以 {@link DynamicFixedDelayTrigger} 注册到独立的 {@link ThreadPoolTaskScheduler}，
 * 间隔在每轮排期时实时读取系统动态配置（{@code workerDelayMs}/{@code scheduleDelayMs}）。
 * 管理员修改配置并提交后，下一轮排期即采用新间隔，无需重启，也不重复注册。</p>
 *
 * <p>P1 事件唤醒（{@code AgentImmediateWakeListener}）直接向 {@code agentRunTaskExecutor}
 * 提交 claim，绕过本调度器；多实例下由 {@code FOR UPDATE SKIP LOCKED} 保证同一运行只被
 * 一个实例领取。</p>
 *
 * <p>生命周期由 SmartLifecycle 管理：start 只发生一次，stop 时销毁调度线程池，
 * 不产生线程泄漏；Agent 运行时被禁用（{@code agent.runtime-allowed=false}）时
 * 跳过 Worker 轮询注册，规划恢复调度保持原有行为。</p>
 *
 * <p>测试与特殊部署可用 {@code agent.scheduling-enabled=false} 关闭本注册器的全部
 * 动态调度，但保留 Agent 运行时、执行器与业务 Bean 的完整装配。生产默认 true。</p>
 */
@Component
public class AgentDynamicScheduleRegistrar implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AgentDynamicScheduleRegistrar.class);
    private static final long DEFAULT_WORKER_DELAY_MS = 1000L;
    private static final long DEFAULT_SCHEDULE_DELAY_MS = 30000L;

    private final ObjectProvider<AgentRuntimeJob> runtimeJobProvider;
    private final AgentPlanningRecoveryJob planningRecoveryJob;
    private final SystemConfigService systemConfigService;
    @Value("${agent.scheduling-enabled:true}")
    private boolean schedulingEnabled = true;

    private volatile ThreadPoolTaskScheduler scheduler;

    public AgentDynamicScheduleRegistrar(
            ObjectProvider<AgentRuntimeJob> runtimeJobProvider,
            AgentPlanningRecoveryJob planningRecoveryJob,
            SystemConfigService systemConfigService) {
        this.runtimeJobProvider = runtimeJobProvider;
        this.planningRecoveryJob = planningRecoveryJob;
        this.systemConfigService = systemConfigService;
    }

    @Override
    public void start() {
        if (!schedulingEnabled) {
            log.info("Agent 动态调度已关闭（agent.scheduling-enabled=false），跳过注册");
            return;
        }
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("agent-dynamic-schedule-");
        taskScheduler.setWaitForTasksToCompleteOnShutdown(false);
        taskScheduler.initialize();
        this.scheduler = taskScheduler;

        AgentRuntimeJob runtimeJob = runtimeJobProvider.getIfAvailable();
        if (runtimeJob != null) {
            taskScheduler.schedule(runtimeJob::tick, new DynamicFixedDelayTrigger(this::workerDelayMillis));
        } else {
            log.info("Agent 运行时未启用（agent.runtime-allowed=false），跳过 Worker 轮询调度注册");
        }
        taskScheduler.schedule(planningRecoveryJob::tick, new DynamicFixedDelayTrigger(this::scheduleDelayMillis));
        log.info("Agent 动态调度注册完成：workerDelayMs={}, scheduleDelayMs={}",
                workerDelayMillis(), scheduleDelayMillis());
    }

    @Override
    public void stop() {
        ThreadPoolTaskScheduler taskScheduler = this.scheduler;
        this.scheduler = null;
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    long workerDelayMillis() {
        long configured;
        try {
            configured = clamp(systemConfigService.getWorkerDelayMs(),
                    SystemConfig.MIN_WORKER_DELAY, SystemConfig.MAX_WORKER_DELAY);
        } catch (Exception e) {
            log.warn("读取动态 Worker 轮询间隔失败，回退默认值: {}", e.getMessage());
            configured = DEFAULT_WORKER_DELAY_MS;
        }
        return configured;
    }

    long scheduleDelayMillis() {
        try {
            return clamp(systemConfigService.getScheduleDelayMs(),
                    SystemConfig.MIN_SCHEDULE_DELAY, SystemConfig.MAX_SCHEDULE_DELAY);
        } catch (Exception e) {
            log.warn("读取动态定时检测间隔失败，回退默认值: {}", e.getMessage());
            return DEFAULT_SCHEDULE_DELAY_MS;
        }
    }

    private long clamp(long value, long min, long max) {
        return Math.min(Math.max(value, min), max);
    }
}
