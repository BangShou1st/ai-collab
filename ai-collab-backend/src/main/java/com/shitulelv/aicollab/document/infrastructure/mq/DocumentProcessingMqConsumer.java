package com.shitulelv.aicollab.document.infrastructure.mq;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RocketMQ 文档链路消费者生命周期管理（M1 迁移，{@code document.mq.enabled=true} 时装配）。
 *
 * <p>消费者懒启动 + 周期性重连：broker 尚未就绪（如 compose 内 broker 慢启动、
 * 网络抖动）时应用仍可正常启动，由 {@link Scheduled} 重连任务按
 * {@code document.mq.reconnect-interval} 反复尝试；每次尝试失败即丢弃实例
 * （DefaultMQPushConsumer 内部状态机 START_FAILED 后不可复用），下次重建。</p>
 *
 * <p>消费失败期间文档不会丢：消费不到的消息停在 broker，已消费未完成的状态行
 * 由恢复 Job 对账兜底 —— 与"消息丢失无害"总设计一致。</p>
 */
public class DocumentProcessingMqConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingMqConsumer.class);

    private final DocumentMqProperties properties;
    private final DocumentProcessingMqListener listener;

    /** 运行中的消费者实例；null 表示当前不可用（等待重连）。 */
    private volatile DefaultMQPushConsumer consumer;
    private final Object consumerLock = new Object();

    public DocumentProcessingMqConsumer(DocumentMqProperties properties,
                                        DocumentProcessingMqListener listener) {
        this.properties = properties;
        this.listener = listener;
    }

    @PostConstruct
    void start() {
        tryStart();
    }

    /** broker 恢复后自动接入；已在运行则空操作。 */
    @Scheduled(fixedDelayString = "${document.mq.reconnect-interval:60s}")
    void reconnectIfDown() {
        if (consumer == null) {
            tryStart();
        }
    }

    @PreDestroy
    void stop() {
        DefaultMQPushConsumer active = this.consumer;
        this.consumer = null;
        if (active != null) {
            active.shutdown();
        }
    }

    private void tryStart() {
        synchronized (consumerLock) {
            if (this.consumer != null) {
                return;
            }
            DefaultMQPushConsumer fresh = new DefaultMQPushConsumer(properties.consumerGroup());
            fresh.setNamesrvAddr(properties.namesrvAddr());
            try {
                // 消费并发度与进程内 documentTaskExecutor 容量对齐（core=max=4），
                // 避免 MQ 模式下 Embedding 出站压力显著超过既有进程内路径基线
                fresh.setConsumeThreadMin(4);
                fresh.setConsumeThreadMax(4);
                // 批次固定 1：单条消息的 ACK / RECONSUME 语义一一对应，幂等判定不跨消息
                fresh.setConsumeMessageBatchMaxSize(1);
                // 重试耗尽（达到 maxReconsumeTimes）后客户端自动转投 %DLQ%<group>
                fresh.setMaxReconsumeTimes(properties.maxReconsumeTimes());
                fresh.registerMessageListener(listener);
                fresh.subscribe(properties.topic(), "*");
                fresh.start();
                this.consumer = fresh;
                log.info("RocketMQ 文档链路消费者已启动，topic={}，group={}，namesrv={}",
                        properties.topic(), properties.consumerGroup(), properties.namesrvAddr());
            } catch (Exception exception) {
                fresh.shutdown();
                log.warn("RocketMQ 消费者启动失败，等待周期重连，topic={}，namesrv={}，type={}",
                        properties.topic(), properties.namesrvAddr(),
                        exception.getClass().getSimpleName());
            }
        }
    }
}
