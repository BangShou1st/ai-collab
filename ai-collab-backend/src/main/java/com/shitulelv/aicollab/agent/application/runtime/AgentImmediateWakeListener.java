package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 进程内即时唤醒实现（设计文档 §3.4 阶段 1）。
 *
 * <p>收到 {@link AgentDelegationWakeEvent} 后直接向 {@code agentRunTaskExecutor}
 * 提交一次 claim 尝试，绕过 1s 调度 tick；{@code claimNext} 的
 * {@code FOR UPDATE SKIP LOCKED} 保证与 tick 竞争不出双跑。</p>
 *
 * <p>异常边界：本监听器<b>吞掉一切异常</b>（含 TaskRejectedException 与 claim/process
 * 的运行时异常）——唤醒是优化而非常确性依赖，调度 tick 兜底仍在；监听器在子终态
 * 事务提交后运行，任何失败都不得外溢。</p>
 *
 * <p>与 {@code AgentRuntimeJob} 相同的 {@code agent.runtime-allowed} 开关：
 * 运行时被管理端禁用时唤醒路径一并停用。</p>
 */
@Component
@ConditionalOnProperty(prefix = "agent", name = "runtime-allowed", havingValue = "true", matchIfMissing = true)
public class AgentImmediateWakeListener implements AgentRunWakeListener {
    private static final Logger log = LoggerFactory.getLogger(AgentImmediateWakeListener.class);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(6);

    private final AgentRecoveryJob recovery;
    private final AgentWorker worker;
    private final TaskExecutor agentRunTaskExecutor;
    private final String workerId = "wake-" + UUID.randomUUID();

    public AgentImmediateWakeListener(
            AgentRecoveryJob recovery,
            AgentWorker worker,
            @Qualifier("agentRunTaskExecutor") TaskExecutor agentRunTaskExecutor) {
        this.recovery = recovery;
        this.worker = worker;
        this.agentRunTaskExecutor = agentRunTaskExecutor;
    }

    /** Spring 事件入口：AgentDelegationWakeEvent 已由发布方保证在事务提交后发布。 */
    @EventListener
    public void onDelegationWake(AgentDelegationWakeEvent event) {
        wake(event.parentRunId());
    }

    @Override
    public void wake(UUID parentRunId) {
        try {
            agentRunTaskExecutor.execute(() -> {
                try {
                    // 一次 claim 尝试：领到父运行即处理，领不到（被 tick 抢先或尚不可领）直接结束。
                    recovery.claim(workerId, CLAIM_LEASE).ifPresent(worker::process);
                } catch (RuntimeException failure) {
                    // 唤醒是优化非正确性依赖：失败只记录类型，tick 兜底
                    log.warn("委派唤醒后的 claim 尝试失败，等待调度 tick 兜底，type={}",
                            failure.getClass().getSimpleName());
                }
            });
        } catch (TaskRejectedException rejected) {
            // 执行器满载：等待中的父运行保持排队，由后续 tick 领取
            log.debug("委派唤醒被拒绝（执行器满载），由调度 tick 兜底");
        } catch (RuntimeException failure) {
            log.warn("委派唤醒提交失败，等待调度 tick 兜底，type={}", failure.getClass().getSimpleName());
        }
    }
}
