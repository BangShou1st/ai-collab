package com.shitulelv.aicollab.planning.infrastructure.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 规划生成链路 MQ 配置（M2 迁移 §3.1 第 2 行：dispatch 消息化）。
 *
 * <p>总闸 {@code planning.mq.enabled} 默认取全局 {@code MQ_ENABLED}（可经
 * {@code PLANNING_MQ_ENABLED} 单独覆盖）：false 时本链路完全回落进程内
 * FutureTask 派发路径（回落模式仅限单实例/本地部署）。</p>
 */
@ConfigurationProperties(prefix = "planning.mq")
public record PlanningMqProperties(
        boolean enabled,
        String namesrvAddr,
        String topic,
        String producerGroup,
        String consumerGroup,
        int maxReconsumeTimes,
        Duration reconnectInterval) {

    public PlanningMqProperties {
        // 关键参数的兜底默认值：正常情况下由 application.yml 提供，防止 yml 缺项导致 NPE
        if (namesrvAddr == null || namesrvAddr.isBlank()) namesrvAddr = "rocketmq-namesrv:9876";
        if (topic == null || topic.isBlank()) topic = "plan-generation";
        if (producerGroup == null || producerGroup.isBlank()) producerGroup = "plan-generation-producer";
        if (consumerGroup == null || consumerGroup.isBlank()) consumerGroup = "plan-generation-consumer";
        if (maxReconsumeTimes <= 0) maxReconsumeTimes = 3;
        if (reconnectInterval == null || reconnectInterval.isNegative() || reconnectInterval.isZero()) {
            reconnectInterval = Duration.ofSeconds(60);
        }
    }
}
