package com.shitulelv.aicollab.agent.job;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * AI 任务补偿扫描（P1 契约 §4.4）。
 *
 * <p>每 {@code memory.job.compensation-interval}（{@code AI_TASK_COMPENSATION_INTERVAL}，默认 30s）执行一次，
 * 先用 Redisson 锁 {@code aicollab:lock:ai-task-compensation} 保证只有一个实例在扫：{@code tryLock(0, -1)}
 * 即不等待、看门狗续期。即使锁丢失（看门狗失联、Redis 故障切换），每条推进都是带 version 的条件更新，
 * 同一任务也只会被推进一次。</p>
 *
 * <p>只在 {@code memory.job.enabled=true} 时装配；关闭时既不扫描也不会建 Redisson 连接。</p>
 */
@Component
@ConditionalOnExpression("${memory.job.enabled:false} || ${knowledge.index.jobs-enabled:false}")
public class AiTaskCompensationJob {

    public static final String LOCK_KEY = "aicollab:lock:ai-task-compensation";
    static final int SCAN_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(AiTaskCompensationJob.class);

    private final AiJobService jobs;
    private final RedissonClient redisson;
    private final Clock clock;
    private AiIndexReindexCompensator reindexCompensator;

    @Autowired
    public AiTaskCompensationJob(AiJobService jobs, RedissonClient redisson) {
        this(jobs, redisson, Clock.systemUTC());
    }

    AiTaskCompensationJob(AiJobService jobs, RedissonClient redisson, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Autowired(required = false)
    void setReindexCompensator(AiIndexReindexCompensator reindexCompensator) {
        this.reindexCompensator = reindexCompensator;
    }

    @Scheduled(fixedDelayString = "${memory.job.compensation-interval:30s}",
            initialDelayString = "${memory.job.compensation-initial-delay:15s}")
    public void scheduledRun() {
        runOnce();
    }

    /**
     * 拿到锁才扫一轮；没拿到锁（别的实例在扫）返回 {@code null}。Redis 不可用时只记日志，不抛出。
     */
    public AiJobService.CompensationSummary runOnce() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked;
        try {
            locked = lock.tryLock(0, -1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException unavailable) {
            log.warn("ai task compensation skipped: lock unavailable error={}",
                    unavailable.getClass().getSimpleName());
            return null;
        }
        if (!locked) {
            return null;
        }
        try {
            return scan();
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException ignored) {
                // 锁已过期或 Redis 断开：下一轮重新竞争，正确性由条件更新保证。
            }
        }
    }

    /** 不经锁直接扫一轮（锁之外的正确性测试入口）。 */
    AiJobService.CompensationSummary scan() {
        AiJobService.CompensationSummary summary = jobs.compensate(OffsetDateTime.now(clock), SCAN_LIMIT);
        if (!summary.idle()) {
            log.info("ai task compensation redispatched={} requeued={} exhausted={} expired={}",
                    summary.redispatched(), summary.requeued(), summary.exhausted(), summary.expired());
        }
        if (reindexCompensator != null) {
            AiIndexReindexCompensator.AdvanceSummary advance = reindexCompensator.advanceFannedOut(SCAN_LIMIT);
            if (!advance.idle()) {
                log.info("ai reindex compensation activated={} abandoned={} reconciledDispatched={}",
                        advance.activated(), advance.abandoned(), advance.reconciledDispatched());
            }
        }
        return summary;
    }
}
