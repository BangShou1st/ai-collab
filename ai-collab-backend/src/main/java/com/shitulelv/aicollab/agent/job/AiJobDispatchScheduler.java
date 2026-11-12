package com.shitulelv.aicollab.agent.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * QUEUED Job 的真实派发触发（W5 冻结 §3.2）。
 *
 * <p>默认 {@code memory.job.enabled=false} 时不装配；只有显式开启后才扫描队列。
 * 每个 Job 仍由 {@link AiJobService#lease} 的 CAS 负责抢占，多个应用实例不会重复派发。
 * 单条失败只记录固定类型，不阻塞本轮其它 Job，也不把异常正文写入日志。</p>
 */
@Component
@ConditionalOnProperty(name = "memory.job.enabled", havingValue = "true")
public class AiJobDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiJobDispatchScheduler.class);
    private static final int SCAN_LIMIT = 20;

    private final AiJobRepository repository;
    private final AiJobDispatcher dispatcher;

    public AiJobDispatchScheduler(AiJobRepository repository, AiJobDispatcher dispatcher) {
        this.repository = repository;
        this.dispatcher = dispatcher;
    }

    /** 队列扫描与派发；fixedDelay 保证上一轮结束后才进入下一轮。 */
    @Scheduled(fixedDelayString = "${memory.job.dispatch-delay-ms:5000}",
            initialDelayString = "${memory.job.dispatch-initial-delay-ms:3000}")
    public void dispatchQueued() {
        for (AiJobRecord job : repository.findQueued(OffsetDateTime.now(), SCAN_LIMIT)) {
            try {
                dispatcher.dispatch(job.id());
            } catch (RuntimeException failure) {
                log.warn("AI Job 派发跳过: jobId={} reason={}", job.id(),
                        failure.getClass().getSimpleName());
            }
        }
    }
}
