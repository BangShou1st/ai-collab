package com.shitulelv.aicollab.document.infrastructure.mq;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 文档链路 MQ 指标（M1 迁移）。命名与既有 {@code agent.*} 指标族保持一致：
 * <ul>
 *   <li>{@code document.mq.sent{outcome}} —— 事务/普通消息发送结果（ok / failed）</li>
 *   <li>{@code document.mq.consumed{outcome}} —— 消费结果（ok = 已处理，dropped = 幂等丢弃）</li>
 *   <li>{@code document.mq.failed{stage}} —— 消费异常将进入重试/重投递</li>
 *   <li>{@code document.mq.deadletter} —— 重试耗尽、消息即将转入死信队列（告警看板项）</li>
 * </ul>
 * 指标失败不允许影响业务结果（与 {@code AgentRuntimeMetrics} 同一约定）。
 */
@Component
public class DocumentMqMetrics {

    private final MeterRegistry registry;

    public DocumentMqMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void sent(String outcome) {
        counter("document.mq.sent", "outcome", outcome);
    }

    public void consumed(String outcome) {
        counter("document.mq.consumed", "outcome", outcome);
    }

    public void failed(String stage) {
        counter("document.mq.failed", "stage", stage);
    }

    public void deadLetter(String stage) {
        counter("document.mq.deadletter", "stage", stage);
    }

    private void counter(String name, String tagKey, String tagValue) {
        try {
            registry.counter(name, tagKey, tagValue == null ? "unknown" : tagValue).increment();
        } catch (RuntimeException ignored) {
            // 指标异常不得改变业务结果
        }
    }
}
