package com.shitulelv.aicollab.document.infrastructure.mq;

import com.shitulelv.aicollab.document.application.service.DocumentTaskDispatcher;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 文档处理消息消费处理器测试：状态预检、ACK 和异常重投语义。 */
class DocumentProcessingMqHandlerTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentTaskDispatcher dispatcher = mock(DocumentTaskDispatcher.class);
    private final DocumentMqMetrics metrics = mock(DocumentMqMetrics.class);
    private final DocumentProcessingMqHandler handler =
            new DocumentProcessingMqHandler(documents, dispatcher, metrics);

    private UUID projectId;
    private UUID documentId;
    private DocumentProcessingMessage message;

    @BeforeEach
    void setUp() {
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

            verify(dispatcher, never()).runOnce(any(), any());
        }
    }

    @Test
    void missingDocumentIsDroppedWithoutExecution() {
        when(documents.find(projectId, documentId)).thenReturn(Optional.empty());

        handler.handle(message);

        verifyNoInteractions(dispatcher);
    }

    @Test
    void uploadedDocumentReachesDatabaseClaimPath() {
        when(documents.find(projectId, documentId))
                .thenReturn(Optional.of(entity(documentId, DocumentStatus.UPLOADED)));

        handler.handle(message);

        // 并发重复消息由处理服务的 UPLOADED→PARSING CAS 决出唯一获胜者。
        verify(dispatcher).runOnce(projectId, documentId);
    }

    @Test
    void preClaimFailureCanBeRetriedWithoutStaleReservation() {
        when(documents.find(projectId, documentId))
                .thenReturn(Optional.of(entity(documentId, DocumentStatus.UPLOADED)));
        doThrow(new IllegalStateException("claim 前数据库瞬时不可用"))
                .doNothing().when(dispatcher).runOnce(projectId, documentId);

        assertThatThrownBy(() -> handler.handle(message))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> handler.handle(message)).doesNotThrowAnyException();

        // 首次异常可转为 RECONSUME_LATER；重投不受消息外占位阻塞。
        verify(dispatcher, times(2)).runOnce(projectId, documentId);
    }

    @Test
    void systemFailurePropagatesForRetry() {
        when(documents.find(projectId, documentId))
                .thenReturn(Optional.of(entity(documentId, DocumentStatus.UPLOADED)));
        doThrow(new IllegalStateException("数据库瞬时不可用")).when(dispatcher).runOnce(any(), any());

        assertThatThrownBy(() -> handler.handle(message))
                .isInstanceOf(IllegalStateException.class);
    }

    private DocumentEntity entity(UUID currentDocumentId, DocumentStatus status) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(currentDocumentId);
        entity.setProjectId(projectId);
        entity.setStatus(status);
        return entity;
    }
}
