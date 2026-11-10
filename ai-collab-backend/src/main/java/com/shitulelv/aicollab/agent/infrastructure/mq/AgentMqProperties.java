package com.shitulelv.aicollab.agent.infrastructure.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 链路 MQ 配置（M2 迁移 §3.1 第 3 行：run 入队通知双轨）。
 *
 * <p>总闸 {@code agent.mq.enabled} 默认取全局 {@code MQ_ENABLED}（可经
 * {@code AGENT_MQ_ENABLED} 单独覆盖）：false 时本链路完全回落轮询兜底路径
 * （AgentRuntimeJob tick + P1 进程内事件唤醒），不装配任何 MQ Bean。</p>
 */
@ConfigurationProperties(prefix = "agent.mq")
public record AgentMqProperties(
        boolean enabled,
        String namesrvAddr,
        String topic,
        String producerGroup,
        String consumerGroup,
        Duration reconnectInterval,
        /** MQ 触发为主时的兜底轮询下限（毫秒）：默认 5000，env AGENT_MQ_FALLBACK_POLL_MS 可覆盖。 */
        Long fallbackPollMs) {

    public AgentMqProperties {
        // 关键参数的兜底默认值：正常情况下由 application.yml 提供，防止 yml 缺项导致 NPE
        if (namesrvAddr == null || namesrvAddr.isBlank()) namesrvAddr = "rocketmq-namesrv:9876";
        if (topic == null || topic.isBlank()) topic = "agent-run-queued";
        if (producerGroup == null || producerGroup.isBlank()) producerGroup = "agent-run-queued-producer";
        if (consumerGroup == null || consumerGroup.isBlank()) consumerGroup = "agent-run-queued-consumer";
        if (reconnectInterval == null || reconnectInterval.isNegative() || reconnectInterval.isZero()) {
            reconnectInterval = Duration.ofSeconds(60);
        }
        if (fallbackPollMs == null || fallbackPollMs <= 0) fallbackPollMs = 5000L;
    }
}
