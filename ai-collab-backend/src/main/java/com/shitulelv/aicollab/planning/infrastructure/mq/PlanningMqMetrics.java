package com.shitulelv.aicollab.planning.infrastructure.mq;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 规划链路 MQ 指标（M2 迁移）。命名与既有 {@code document.mq.*} / {@code agent.mq.*}
 * 指标族保持一致：
 * <ul>
 *   <li>{@code planning.mq.sent{outcome}} —— dispatch 消息发送结果（ok / failed）；
 *       发送失败回落进程内派发，仅计数观测</li>
 *   <li>{@code planning.mq.consumed{outcome}} —— 消费结果（ok = 触发执行入口，
 *       dropped = 重复投递/畸形消息/计划已取消被幂等丢弃）</li>
 *   <li>{@code planning.mq.failed{stage}} —— 消费异常将进入重试/重投递</li>
 * </ul>
 * 指标失败不允许影响业务结果（与 {@code AgentRuntimeMetrics} 同一约定）。
 */
@Component
public class PlanningMqMetrics {

    private final MeterRegistry registry;

    public PlanningMqMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void sent(String outcome) {
        counter("planning.mq.sent", "outcome", outcome);
    }

    public void consumed(String outcome) {
        counter("planning.mq.consumed", "outcome", outcome);
    }

    public void failed(String stage) {
        counter("planning.mq.failed", "stage", stage);
    }

    private void counter(String name, String tagKey, String tagValue) {
        try {
            registry.counter(name, tagKey, tagValue == null ? "unknown" : tagValue).increment();
        } catch (RuntimeException ignored) {
            // 指标异常不得改变业务结果
        }
    }
}
