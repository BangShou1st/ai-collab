package com.shitulelv.aicollab.agent.job;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 孤儿索引行定期 {@code PRUNE} 兜底调度器（P2 契约 §12.6 R4-2/R4-3）。
 *
 * <p>默认每 6 小时执行一次，使用独立 Redisson 锁 {@code aicollab:lock:ai-index-prune}。
 * 与 {@link AiJobConfiguration} / {@link AiTaskCompensationJob} 一致，仅在 {@code memory.job.enabled=true}
 * （{@code RedissonClient} 已装配）时启用，避免默认未启 Redis 场景下启动失败。</p>
 */
@Component
@ConditionalOnExpression("${memory.job.enabled:false} || ${knowledge.index.jobs-enabled:false}")
public class AiIndexPruneJob {

    public static final String LOCK_KEY = "aicollab:lock:ai-index-prune";
    private static final Logger log = LoggerFactory.getLogger(AiIndexPruneJob.class);

    private final AiIndexPruneService pruneService;
    private final RedissonClient redisson;

    @Autowired
    public AiIndexPruneJob(AiIndexPruneService pruneService, RedissonClient redisson) {
        this.pruneService = Objects.requireNonNull(pruneService, "pruneService");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
    }

    @Scheduled(fixedDelayString = "${ai.index.prune-interval:PT6H}",
            initialDelayString = "${ai.index.prune-initial-delay:PT5M}")
    public void scheduledRun() {
        runOnce();
    }

    /** 抢到锁才执行一轮全库 PRUNE；未拿到锁返回 {@code null}。Redis 不可用只记日志，不抛出。 */
    public AiIndexPruneService.PruneSummary runOnce() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked;
        try {
            locked = lock.tryLock(0, -1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException unavailable) {
            log.warn("ai index prune skipped: lock unavailable error={}",
                    unavailable.getClass().getSimpleName());
            return null;
        }
        if (!locked) {
            return null;
        }
        try {
            return pruneService.pruneAll();
        } catch (RuntimeException failure) {
            log.warn("ai index prune run failed error={}", failure.getClass().getSimpleName());
            return null;
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException ignored) {
                // 锁已过期或 Redis 断开时忽略。
            }
        }
    }
}
