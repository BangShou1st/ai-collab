package com.shitulelv.aicollab.document.infrastructure.mq;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 文档处理消息并发监听器测试：ACK / RECONSUME_LATER / 重试耗尽死信计数的判定路径。
 */
class DocumentProcessingMqListenerTest {

    private final DocumentProcessingMqHandler handler = mock(DocumentProcessingMqHandler.class);
    private final DocumentMqMetrics metrics = mock(DocumentMqMetrics.class);
    private final ConsumeConcurrentlyContext context = mock(ConsumeConcurrentlyContext.class);
    private final DocumentProcessingMqListener listener =
            new DocumentProcessingMqListener(handler, metrics, 5);

    private UUID projectId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        documentId = UUID.randomUUID();
    }

    @Test
    void successfulConsumptionReturnsConsumeSuccess() {
        MessageExt message = messageExt(0);

        ConsumeConcurrentlyStatus status = listener.consumeMessage(List.of(message), context);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(handler).handle(any(DocumentProcessingMessage.class));
    }

    @Test
    void consumptionFailureReturnsReconsumeLater() {
        MessageExt message = messageExt(0);
        doThrow(new IllegalStateException("数据库瞬时不可用")).when(handler).handle(any());

        ConsumeConcurrentlyStatus status = listener.consumeMessage(List.of(message), context);

        // 系统级异常：延迟等级指数退避重试
        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    @Test
    void exhaustedRetriesCountDeadLetterAndStillReturnReconsumeLater() {
        // reconsumeTimes 达到上限：客户端随后把消息转入死信队列 %DLQ%<group>
        MessageExt message = messageExt(5);
        doThrow(new IllegalStateException("持续失败")).when(handler).handle(any());

        ConsumeConcurrentlyStatus status = listener.consumeMessage(List.of(message), context);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        // 死信计数 + 告警日志是消费者侧唯一的死信处理（状态行 + 恢复 Job 兜底业务）
        verify(metrics, times(1)).deadLetter(DocumentProcessingMessage.STAGE_PARSE);
    }

    @Test
    void identityPropertiesMissingIsDroppedWithoutRetry() {
        MessageExt message = new MessageExt();
        message.setTopic("document-processing");
        message.setBody(new byte[0]);

        ConsumeConcurrentlyStatus status = listener.consumeMessage(List.of(message), context);

        // 身份属性非法无法定位状态行，重试无意义：ACK 丢弃
        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(handler, times(0)).handle(any());
    }

    private MessageExt messageExt(int reconsumeTimes) {
        MessageExt message = new MessageExt();
        message.setTopic("document-processing");
        message.setBody(new byte[0]);
        message.putUserProperty(DocumentProcessingMessage.PROP_PROJECT_ID, projectId.toString());
        message.putUserProperty(DocumentProcessingMessage.PROP_DOCUMENT_ID, documentId.toString());
        message.putUserProperty(DocumentProcessingMessage.PROP_STAGE, DocumentProcessingMessage.STAGE_PARSE);
        message.setReconsumeTimes(reconsumeTimes);
        return message;
    }
}
