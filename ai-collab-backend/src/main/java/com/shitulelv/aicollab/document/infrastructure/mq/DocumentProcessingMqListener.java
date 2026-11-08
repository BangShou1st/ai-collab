package com.shitulelv.aicollab.document.infrastructure.mq;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 文档处理消息的并发消费监听器（M1 迁移）。
 *
 * <p>重试语义：处理异常返回 RECONSUME_LATER，客户端按延迟等级（1s/5s/10s/30s/1m/…）
 * 指数退避重投递；超过 {@code maxReconsumeTimes} 后消息进入死信队列
 * {@code %DLQ%<consumerGroup>}。消费者侧不做死信业务处理 —— 状态行 + 恢复 Job
 * 已兜底 —— 但死信必须可观测：计数器 {@code document.mq.deadletter} + ERROR 告警日志。</p>
 */
public class DocumentProcessingMqListener implements MessageListenerConcurrently {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingMqListener.class);

    private final DocumentProcessingMqHandler handler;
    private final DocumentMqMetrics metrics;
    private final int maxReconsumeTimes;

    public DocumentProcessingMqListener(DocumentProcessingMqHandler handler,
                                        DocumentMqMetrics metrics,
                                        int maxReconsumeTimes) {
        this.handler = handler;
        this.metrics = metrics;
        this.maxReconsumeTimes = maxReconsumeTimes;
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                    ConsumeConcurrentlyContext context) {
        // 消费批次固定为 1（consumeMessageBatchMaxSize=1），逐条语义与返回值一一对应
        for (MessageExt message : messages) {
            return consumeOne(message);
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private ConsumeConcurrentlyStatus consumeOne(MessageExt message) {
        DocumentProcessingMessage payload;
        try {
            payload = DocumentProcessingMessage.fromProperties(message);
        } catch (RuntimeException exception) {
            // 身份属性缺失/非法：业务上无法定位状态行，重试毫无意义，ACK 丢弃并告警
            log.error("文档消息身份属性非法，丢弃（不重试），messageId={}，msg={}",
                    message.getMsgId(), exception.getMessage());
            metrics.consumed("dropped");
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        try {
            handler.handle(payload);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (RuntimeException exception) {
            // 系统级异常：延迟等级指数退避重试（1s→5s→10s→…）
            metrics.failed(payload.stage());
            if (message.getReconsumeTimes() >= maxReconsumeTimes) {
                // 重试耗尽：客户端将消息转入死信队列 %DLQ%<group>（topic 已由
                // rocketmq-topic-init 预建）。状态行 + 恢复 Job 兜底，此处只做可观测。
                metrics.deadLetter(payload.stage());
                log.error("文档消息重试耗尽（{} 次），将转入死信队列，"
                                + "状态行与恢复 Job 兜底，projectId={}，documentId={}，stage={}，type={}",
                        maxReconsumeTimes, payload.projectId(),
                        payload.documentId(), payload.stage(), exception.getClass().getSimpleName());
            } else {
                log.warn("文档消息消费失败，延迟重试（已重试 {} 次），projectId={}，documentId={}，type={}",
                        message.getReconsumeTimes(), payload.projectId(),
                        payload.documentId(), exception.getClass().getSimpleName());
            }
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }
}
