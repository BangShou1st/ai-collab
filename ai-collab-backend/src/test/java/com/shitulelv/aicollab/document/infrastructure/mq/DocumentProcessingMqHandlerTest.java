package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.document.application.service.DocumentTaskDispatcher;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文档处理消息消费处理器测试：幂等三重保障的前两重（状态前置校验 / 幂等占位）。
 * 第三重（claim CAS）由 Postgres 集成测试覆盖。
 */
class DocumentProcessingMqHandlerTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentMqIdempotencyRepository idempotency = mock(DocumentMqIdempotencyRepository.class);
    private final DocumentTaskDispatcher dispatcher = mock(DocumentTaskDispatcher.class);
    private final DocumentMqMetrics metrics = mock(DocumentMqMetrics.class);
    private final DocumentMqProperties properties = new DocumentMqProperties(
            true, "rocketmq-namesrv:9876", "document-processing",
            "document-processing-producer", "document-processing-consumer", 5,
            java.time.Duration.ofSeconds(60));
    private final DocumentProcessingMqHandler handler =
            new DocumentProcessingMqHandler(documents, idempotency, dispatcher, metrics, properties);

    private UUID uploaderId;
    private UUID projectId;
    private UUID documentId;
    private DocumentProcessingMessage message;

    @BeforeEach
    void setUp() {
        uploaderId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        message = DocumentProcessingMessage.parse(projectId, documentId);
    }

    @Test
    void statusNotUploadedIsDroppedWithoutExecution() {
        for (DocumentStatus status : DocumentStatus.values()) {
            if (status == DocumentStatus.UPLOADED) continue;
            UUID currentDocumentId = UUID.randomUUID();
            DocumentProcessingMessage currentMessage = DocumentProcessingMessage.parse(projectId, currentDocumentId);
            when(documents.find(projectId, currentDocumentId))
                    .thenReturn(Optional.of(entity(currentDocumentId, status)));

            handler.handle(currentMessage);

            // 非法跃迁 / 重复投递：ACK 丢弃，不占用幂等记录、不触发执行
            verify(idempotency, never()).tryAcquire(any(), anyString(), any(), any(), anyString());
            verify(dispatcher, never()).runOnce(any(), any());
        }
    }

    @Test
    void missingDocumentIsDroppedWithoutExecution() {
        when(documents.find(projectId, documentId)).thenReturn(Optional.empty());

        handler.handle(message);

        verifyNoInteractions(idempotency);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void idempotencyConflictIsDroppedWithoutExecution() {
        when(documents.find(projectId, documentId))
                .thenReturn(Optional.of(entity(documentId, DocumentStatus.UPLOADED)));
        when(idempotency.tryAcquire(eq(uploaderId), eq("mq:document-processing:parse"),
                eq(projectId), eq(documentId), eq(DocumentProcessingMessage.STAGE_PARSE)))
                .thenReturn(false);

        handler.handle(message);

        // 同 documentId+stage 二次投递：已有实例受理，直接丢弃
        verify(dispatcher, never()).runOnce(any(), any());
    }

    @Test
    void acquiredTokenTriggersSharedExecutionPath() {
        when(documents.find(projectId, documentId))
                .thenReturn(Optional.of(entity(documentId, DocumentStatus.UPLOADED)));
        when(idempotency.tryAcquire(any(), anyString(), any(), any(), anyString())).thenReturn(true);

        handler.handle(message);

        // 消费触发共享执行方法（与进程内路径共用）
        verify(dispatcher).runOnce(projectId, documentId);
    }

    @Test
    void systemFailureReleasesTokenAndPropagatesForRetry() {
        when(documents.find(projectId, documentId))
                .thenReturn(Optional.of(entity(documentId, DocumentStatus.UPLOADED)));
        when(idempotency.tryAcquire(any(), anyString(), any(), any(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("数据库瞬时不可用")).when(dispatcher).runOnce(any(), any());

        assertThatThrownBy(() -> handler.handle(message))
                .isInstanceOf(IllegalStateException.class);

        // 撤销幂等占位让重试投递可重新受理（RECONSUME_LATER 语义）
        verify(idempotency).release(eq(uploaderId), eq("mq:document-processing:parse"), eq(documentId));
    }

    private DocumentEntity entity(UUID currentDocumentId, DocumentStatus status) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(currentDocumentId);
        entity.setProjectId(projectId);
        entity.setStatus(status);
        entity.setUploadedBy(uploaderId);
        return entity;
    }
}
