package com.shitulelv.aicollab.agent.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Job 恢复任务骨架（W5 冻结 §3.2）。
 *
 * <p>只做“扫描 + 调 {@link AiJobService#recoverExpired}”这两件事：租约过期回收、
 * deadline 过期、到期重试 requeue。<b>本波不注册调度器</b>（没有 {@code @Scheduled}，
 * 也不依赖 Python），默认 {@code memory.job.enabled=false} /
 * {@code memory.job.recovery-enabled=false}，两个开关都为 true 时才装配，
 * 由后续波次接定时触发。</p>
 */
@Component
@ConditionalOnProperty(name = {"memory.job.enabled", "memory.job.recovery-enabled"},
        havingValue = "true")
public class AiJobRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(AiJobRecoveryJob.class);

    private static final int SCAN_LIMIT = 100;

    private final AiJobService service;

    public AiJobRecoveryJob(AiJobService service) {
        this.service = service;
    }

    /** 手动/测试入口：执行一轮恢复扫描并返回计数。 */
    public AiJobService.RecoverySummary runOnce() {
        AiJobService.RecoverySummary summary =
                service.recoverExpired(OffsetDateTime.now(), SCAN_LIMIT);
        if (summary.leaseExpired() > 0 || summary.deadlineExpired() > 0
                || summary.requeued() > 0) {
            log.info("AI Job 恢复扫描完成: leaseExpired={} deadlineExpired={} requeued={}",
                    summary.leaseExpired(), summary.deadlineExpired(), summary.requeued());
        }
        return summary;
    }
}
