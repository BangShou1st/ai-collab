package com.shitulelv.aicollab.agent.job;

/**
 * 任务进入 {@code QUEUED}（新建或被补偿扫描重排）后发布的事件（P1 契约 §4.2）。
 *
 * <p>监听方在 {@code AFTER_COMMIT} 阶段向 Redis Stream 执行 XADD；没有事务时立即执行。
 * {@code job} 携带的 {@code leaseEpoch} 就是 Stream 消息里的 epoch。</p>
 */
public record AiJobQueuedEvent(AiJobRecord job) {
}
