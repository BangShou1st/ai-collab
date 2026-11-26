package com.shitulelv.aicollab.document.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.DocumentPipelineMessenger;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档链路 MQ 装配。
 *
 * <p>{@code document.mq.enabled}（env {@code MQ_ENABLED}，默认 true）开启时装配 RocketMQ
 * 事务生产者门面与消费者。该开关只用于运维紧急关闭：关闭后不装配任何消息门面，
 * 文档上传返回 503，不在进程内注册或解析。测试 profile 在 application.properties
 * 中关闭，MQ 集成测试（Testcontainers）单独开启。</p>
 */
@Configuration
@EnableConfigurationProperties(DocumentMqProperties.class)
public class DocumentMqConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "document.mq.enabled", havingValue = "true")
    public DocumentPipelineMessenger rocketMqDocumentPipelineMessenger(DocumentMqProperties properties,
                                                                       DocumentRepository documents,
                                                                       DocumentMqMetrics metrics,
                                                                       ObjectMapper objectMapper) {
        return new RocketMqDocumentPipelineMessenger(properties, documents, metrics, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "document.mq.enabled", havingValue = "true")
    public DocumentProcessingMqConsumer documentProcessingMqConsumer(DocumentMqProperties properties,
                                                                     DocumentProcessingMqHandler handler,
                                                                     DocumentMqMetrics metrics) {
        return new DocumentProcessingMqConsumer(properties,
                new DocumentProcessingMqListener(handler, metrics, properties.maxReconsumeTimes()));
    }
}
