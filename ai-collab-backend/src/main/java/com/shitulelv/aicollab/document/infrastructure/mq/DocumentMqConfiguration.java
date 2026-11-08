package com.shitulelv.aicollab.document.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.DocumentPipelineMessenger;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档链路 MQ 装配（M1 迁移）。
 *
 * <p>总闸 {@code document.mq.enabled}（env {@code MQ_ENABLED}）：</p>
 * <ul>
 *   <li>true —— 装配 RocketMQ 生产者门面与消费者，文档链路消息驱动；</li>
 *   <li>false（默认）—— 两个 MQ Bean 均不创建，仅注册 {@link DisabledDocumentPipelineMessenger}，
 *       上传/重试/批量重建索引完全回落进程内线程池路径。Redis 深化 P2 后回落模式带
 *       Redisson 跨实例互斥锁（见 document/infrastructure/lock，仅回落模式装配）。</li>
 * </ul>
 * 测试 profile 在 application.properties 中显式关闭总闸，避免单元/集成测试依赖 broker。
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

    /** 总闸关闭时的回落实现：enabled()=false，两个发送方法按约定不应被调用。 */
    @Bean
    @ConditionalOnMissingBean(DocumentPipelineMessenger.class)
    public DocumentPipelineMessenger disabledDocumentPipelineMessenger() {
        return new DisabledDocumentPipelineMessenger();
    }
}
