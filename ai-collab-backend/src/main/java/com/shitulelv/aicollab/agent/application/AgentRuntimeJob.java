package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Agent 定时调度入口。
 *
 * <p>调度线程只做短事务：过期审批收口 + 把 Worker 派发到 {@code agentRunTaskExecutor}。
 * 数据库领取（Claim）与模型轮次/工具执行都在派发成功的 Worker Runnable 内进行，
 * 因此模型网络请求不会阻塞 Spring 定时线程。</p>
 */
@Component
@ConditionalOnProperty(prefix = "agent", name = "runtime-allowed", havingValue = "true", matchIfMissing = true)
public class AgentRuntimeJob {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentRuntimeMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shitulelv.aicollab.system.application.service.SystemConfigService systemConfigService;
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeJob.class);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(6);

    private final AgentRecoveryJob recovery;
    private final AgentWorker worker;
    private final AgentApprovalRepository approvals;
    private final TaskExecutor agentRunTaskExecutor;
    private final String workerId = "embedded-" + UUID.randomUUID();

    public AgentRuntimeJob(
            AgentRecoveryJob recovery, AgentWorker worker,
            AgentApprovalRepository approvals,
            @Qualifier("agentRunTaskExecutor") TaskExecutor agentRunTaskExecutor) {
        this.recovery = recovery;
        this.worker = worker;
        this.approvals = approvals;
        this.agentRunTaskExecutor = agentRunTaskExecutor;
    }

    @Scheduled(fixedDelayString = "${agent.worker-delay-ms:1000}")
    public void tick() {
        approvals.expirePending(OffsetDateTime.now(ZoneOffset.UTC));
        if (systemConfigService != null && !systemConfigService.isAgentEnabled()) {
            return;
        }
        try {
            agentRunTaskExecutor.execute(this::runOnce);
        } catch (TaskRejectedException rejected) {
            if (metrics != null) metrics.dispatchRejected();
            // 执行器满载：立即结束本轮 tick，不领取数据库任务、不写失败状态。
            // 等待中的 Run 保持原状态，由后续 tick 或租约过期恢复接管；
            // 用 debug 记录以避免满载时每秒刷出大量 WARN。
            log.debug("Agent Worker 已达到并发上限，本轮 tick 跳过领取，workerId={}", workerId);
        }
    }

    private void runOnce() {
        try {
            // 领取与分发都只在派发成功的 Runnable 内发生：提交被拒绝时不会留下
            // 已领取但无人处理的 Run。
            recovery.claim(workerId, CLAIM_LEASE).ifPresent(worker::process);
        } catch (RuntimeException failure) {
            // 未预期异常只记录类型与 worker 标识，不输出敏感模型内容。
            // Run 的数据库租约保持原样，由既有租约过期与恢复机制接管，
            // 不伪造成功，也不擅自重新执行工具调用。
            log.error("Agent Worker 执行发生未预期异常，workerId={}，type={}",
                    workerId, failure.getClass().getSimpleName());
        }
    }
}
