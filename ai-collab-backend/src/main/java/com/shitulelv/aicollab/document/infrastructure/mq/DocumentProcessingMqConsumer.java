package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.infrastructure.mq.RocketMqConsumerLifecycle;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RocketMQ 文档链路消费者生命周期管理（M1 迁移，{@code document.mq.enabled=true} 时装配）。
 *
 * <p>M2 起实例的懒启动/重连/销毁委托给公共层 {@link RocketMqConsumerLifecycle}
 * （与 agent / planning 链路共用同一份生命周期实现）；本类只负责 Spring 生命周期
 * 挂载与文档链路自己的消费并发度、重试上限等参数。</p>
 *
 * <p>消费失败期间文档不会丢：消费不到的消息停在 broker，已消费未完成的状态行
 * 由恢复 Job 对账兜底 —— 与"消息丢失无害"总设计一致。</p>
 */
public class DocumentProcessingMqConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingMqConsumer.class);

    private final RocketMqConsumerLifecycle lifecycle;

    public DocumentProcessingMqConsumer(DocumentMqProperties properties,
                                        DocumentProcessingMqListener listener) {
        // 消费并发度与进程内 documentTaskExecutor 容量对齐（core=max=4），
        // 避免 MQ 模式下 Embedding 出站压力显著超过既有进程内路径基线
        this.lifecycle = new RocketMqConsumerLifecycle(
                properties.consumerGroup(), properties.namesrvAddr(), properties.topic(),
                4, properties.maxReconsumeTimes(), properties.reconnectInterval(),
                listener, "文档链路");
    }

    @PostConstruct
    void start() {
        lifecycle.start();
    }

    @PreDestroy
    void stop() {
        lifecycle.stop();
    }
}
