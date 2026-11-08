package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文档受理事务消息监听器测试：本地事务执行与 broker 回查裁决。
 * 回查以文档状态行为事实源 —— 行不存在回滚，行存在提交（重复投递由消费侧幂等丢弃）。
 */
class DocumentRegistrationTransactionListenerTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentMqMetrics metrics = mock(DocumentMqMetrics.class);
    private final DocumentRegistrationTransactionListener listener =
            new DocumentRegistrationTransactionListener(documents, metrics);

    private UUID projectId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        documentId = UUID.randomUUID();
    }

    @Test
    void successfulLocalTransactionCommitsHalfMessage() {
        DocumentRegistrationExecution execution =
                new DocumentRegistrationExecution(() -> { /* 注册事务成功 */ });

        LocalTransactionState state = listener.executeLocalTransaction(message(), execution);

        assertThat(state).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        assertThat(execution.error).isNull();
    }

    @Test
    void businessFailureRollsBackHalfMessageAndCapturesError() {
        DocumentRegistrationExecution execution = new DocumentRegistrationExecution(() -> {
            throw new BusinessException(ErrorCode.DOCUMENT_LIMIT_EXCEEDED);
        });

        LocalTransactionState state = listener.executeLocalTransaction(message(), execution);

        // 注册事务已随异常回滚，半消息直接丢弃，异常由发送侧读取后按原语义透传调用方
        assertThat(state).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
        assertThat(execution.error)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_LIMIT_EXCEEDED));
    }

    @Test
    void unexpectedFailureRollsBackHalfMessage() {
        DocumentRegistrationExecution execution = new DocumentRegistrationExecution(() -> {
            throw new IllegalStateException("数据库瞬时不可用");
        });

        LocalTransactionState state = listener.executeLocalTransaction(message(), execution);

        assertThat(state).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
        assertThat(execution.error).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void checkRollsBackWhenStatusRowDoesNotExist() {
        when(documents.find(projectId, documentId)).thenReturn(Optional.empty());

        LocalTransactionState state = listener.checkLocalTransaction(messageExt());

        // 注册事务从未提交：消息无意义，回滚；若恰逢提交竞态窗口，UPLOADED 状态行
        // 仍会被恢复 Job 对账派发（消息丢失无害设计）
        assertThat(state).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
    }

    @Test
    void checkCommitsWhenStatusRowExistsInAnyState() {
        for (DocumentStatus status : DocumentStatus.values()) {
            UUID currentDocumentId = UUID.randomUUID();
            when(documents.find(projectId, currentDocumentId))
                    .thenReturn(Optional.of(entity(status)));

            LocalTransactionState state = listener.checkLocalTransaction(messageExt(currentDocumentId));

            // UPLOADED 等待处理；PARSING/INDEXING 处理中；终态重复投递由消费侧丢弃 —— 均提交
            assertThat(state).as("status=" + status).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        }
    }

    private Message message() {
        Message message = new Message("document-processing", documentId.toString(), new byte[0]);
        message.putUserProperty(DocumentProcessingMessage.PROP_PROJECT_ID, projectId.toString());
        message.putUserProperty(DocumentProcessingMessage.PROP_DOCUMENT_ID, documentId.toString());
        return message;
    }

    private MessageExt messageExt() {
        return messageExt(documentId);
    }

    private MessageExt messageExt(UUID currentDocumentId) {
        MessageExt message = new MessageExt();
        message.setTopic("document-processing");
        message.setBody(new byte[0]);
        message.putUserProperty(DocumentProcessingMessage.PROP_PROJECT_ID, projectId.toString());
        message.putUserProperty(DocumentProcessingMessage.PROP_DOCUMENT_ID, currentDocumentId.toString());
        return message;
    }

    private DocumentEntity entity(DocumentStatus status) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(documentId);
        entity.setProjectId(projectId);
        entity.setStatus(status);
        return entity;
    }
}
