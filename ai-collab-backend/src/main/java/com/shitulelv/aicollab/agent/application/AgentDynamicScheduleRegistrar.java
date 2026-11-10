package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.system.domain.model.SystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>M2 双轨制轮询降频：agent MQ（{@code agent.mq.enabled=true}）开启后，入队通知由
 * MQ 消费者即时触发 claim（多实例安全的触发器），轮询退居兜底角色 —— 此时把兜底
 * 轮询下限抬到 {@code fallbackPollMs}（默认 5000，env {@code AGENT_MQ_FALLBACK_POLL_MS}
 * 可覆盖）：管理员配置的间隔快于下限时按取大处理（MQ 模式下轮询不再承担秒级派发）；
 * 配置的间隔更慢（如 60s）时保持原值。总闸关闭时行为与 M2 之前完全一致（1s 轮询）。</p>
 *
 * <p>P1 事件唤醒（{@code AgentImmediateWakeListener}）与 MQ 消费触发都直接向
 * {@code agentRunTaskExecutor} 提交 claim，完全绕过本调度器 —— 降频不影响两条
 * 即时唤醒路径的时效。</p>
 *
 * <p>生命周期由 SmartLifecycle 管理：start 只发生一次，stop 时销毁调度线程池，
 * 不产生线程泄漏；Agent 运行时被禁用（{@code agent.runtime-allowed=false}）时
 * 跳过 Worker 轮询注册，规划恢复调度保持原有行为。</p>
 */
@Component
public class AgentDynamicScheduleRegistrar implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AgentDynamicScheduleRegistrar.class);
    private static final long DEFAULT_WORKER_DELAY_MS = 1000L;
    private static final long DEFAULT_SCHEDULE_DELAY_MS = 30000L;

    private final ObjectProvider<AgentRuntimeJob> runtimeJobProvider;
    private final AgentPlanningRecoveryJob planningRecoveryJob;
    private final SystemConfigService systemConfigService;
    /** M2 轮询降频读取 agent.mq 配置（enabled + fallbackPollMs）；未装配时按关闭处理。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shitulelv.aicollab.agent.infrastructure.mq.AgentMqProperties agentMqProperties;

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
        // M2 双轨制轮询降频：MQ 开启时兜底轮询不低于 fallbackPollMs（默认 5s）。
        // 取大而非覆盖：管理员配置的更慢轮询保持原值；配置的更快轮询在 MQ 模式下
        // 收敛到兜底下限（秒级派发由 MQ 消费触发承担，见类注释）。
        if (agentMqProperties != null && agentMqProperties.enabled()) {
            return Math.max(configured, agentMqProperties.fallbackPollMs());
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
