package com.shitulelv.aicollab.document.infrastructure.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 文档链路 MQ 配置（M1 迁移）。所有键在 application.yml 中经环境变量注入默认值；
 * {@code document.mq.enabled=false}（即总闸 {@code MQ_ENABLED=false}）时整个 MQ
 * 装配被跳过，文档链路完全回落进程内路径。
 */
@ConfigurationProperties(prefix = "document.mq")
public record DocumentMqProperties(
        boolean enabled,
        String namesrvAddr,
        String topic,
        String producerGroup,
        String consumerGroup,
        int maxReconsumeTimes,
        Duration reconnectInterval) {

    public DocumentMqProperties {
        // 关键参数的兜底默认值：正常情况下由 application.yml 提供，防止 yml 缺项导致 NPE
        if (namesrvAddr == null || namesrvAddr.isBlank()) namesrvAddr = "rocketmq-namesrv:9876";
        if (topic == null || topic.isBlank()) topic = "document-processing";
        if (producerGroup == null || producerGroup.isBlank()) producerGroup = "document-processing-producer";
        if (consumerGroup == null || consumerGroup.isBlank()) consumerGroup = "document-processing-consumer";
        if (maxReconsumeTimes <= 0) maxReconsumeTimes = 5;
        if (reconnectInterval == null || reconnectInterval.isNegative() || reconnectInterval.isZero()) {
            reconnectInterval = Duration.ofSeconds(60);
        }
    }

    /** 消费幂等 endpoint（idempotency_record.endpoint 列）：阶段名进 endpoint。 */
    public String idempotencyEndpoint(String stage) {
        return "mq:document-processing:" + stage;
    }
}
