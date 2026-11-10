package com.shitulelv.aicollab.agent.infrastructure.mq;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 链路 MQ 指标（M2 迁移）。命名与既有 {@code document.mq.*} 指标族保持一致：
 * <ul>
 *   <li>{@code agent.mq.sent{outcome}} —— run 入队通知发送结果（ok / failed）；
 *       发送失败无害（轮询兜底），仅计数观测</li>
 *   <li>{@code agent.mq.consumed{outcome}} —— 消费结果（ok = 触发一次 claim 尝试，
 *       dropped = 无 runId 等畸形消息）</li>
 * </ul>
 * 指标失败不允许影响业务结果（与 {@code AgentRuntimeMetrics} 同一约定）。
 */
@Component
public class AgentMqMetrics {

    private final MeterRegistry registry;

    public AgentMqMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void sent(String outcome) {
        counter("agent.mq.sent", "outcome", outcome);
    }

    public void consumed(String outcome) {
        counter("agent.mq.consumed", "outcome", outcome);
    }

    private void counter(String name, String tagKey, String tagValue) {
        try {
            registry.counter(name, tagKey, tagValue == null ? "unknown" : tagValue).increment();
        } catch (RuntimeException ignored) {
            // 指标异常不得改变业务结果
        }
    }
}
