package com.shitulelv.aicollab.agent.application;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * 每次排期时动态读取延迟值的 fixed-delay 触发器。
 *
 * <p>下一次执行 = 上一次执行完成时刻 + 当前动态延迟；首次执行立即触发，
 * 与原 {@code @Scheduled(fixedDelayString=...)} 语义一致。任务在两次执行之间
 * 不会被并发重入（由 {@code ThreadPoolTaskScheduler} 的 ReschedulingRunnable 保证：
 * 上一轮完成后才按 Trigger 计算下一轮）。</p>
 */
public final class DynamicFixedDelayTrigger implements Trigger {

    private final LongSupplier delayMillisSupplier;

    public DynamicFixedDelayTrigger(LongSupplier delayMillisSupplier) {
        this.delayMillisSupplier = delayMillisSupplier;
    }

    @Override
    public Instant nextExecution(TriggerContext triggerContext) {
        long delayMillis = Math.max(1L, delayMillisSupplier.getAsLong());
        Instant lastCompletion = triggerContext.lastCompletion();
        return lastCompletion == null ? Instant.now() : lastCompletion.plusMillis(delayMillis);
    }
}
