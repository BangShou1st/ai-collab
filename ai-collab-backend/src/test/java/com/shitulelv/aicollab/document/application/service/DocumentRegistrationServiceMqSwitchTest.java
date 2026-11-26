package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文档受理分支：只走事务消息；MQ 关闭或事务消息发不出去时返回 503，绝不在进程内注册。
 * broker 真实不可达的 HTTP 端到端见 {@code DocumentUploadBrokerUnavailableIntegrationTest}。
 */
class DocumentRegistrationServiceMqSwitchTest {

    private final DocumentRegistrationTransaction registration = mock(DocumentRegistrationTransaction.class);
    private final DocumentPipelineMessenger messenger = mock(DocumentPipelineMessenger.class);

    private DocumentEntity document;

    @BeforeEach
    void setUp() {
        document = new DocumentEntity();
        document.setId(UUID.randomUUID());
        document.setProjectId(UUID.randomUUID());
        document.setStatus(DocumentStatus.UPLOADED);
    }

    @Test
    void transactionalMessageRunsRegistrationAsLocalTransaction() {
        // 事务监听器在发送线程内同步执行本地事务
        when(messenger.trySendRegistrationTransaction(eq(document), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });

        service(Optional.of(messenger)).registerUploadedDocument(document);

        verify(registration).register(document);
    }

    @Test
    void sendFailureReturns503WithoutLocalRegistration() {
        when(messenger.trySendRegistrationTransaction(eq(document), any())).thenReturn(false);

        assertPipelineUnavailable(() -> service(Optional.of(messenger)).registerUploadedDocument(document));

        verify(registration, never()).register(any());
    }

    @Test
    void mqClosedReturns503WithoutLocalRegistration() {
        assertPipelineUnavailable(() -> service(Optional.empty()).registerUploadedDocument(document));

        verifyNoInteractions(registration);
    }

    @Test
    void registrationBusinessErrorPropagates() {
        when(messenger.trySendRegistrationTransaction(eq(document), any()))
                .thenThrow(new BusinessException(ErrorCode.DOCUMENT_LIMIT_EXCEEDED));

        assertThatThrownBy(() -> service(Optional.of(messenger)).registerUploadedDocument(document))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_LIMIT_EXCEEDED));
        verify(registration, never()).register(any());
    }

    @Test
    void pipelineUnavailableMapsToHttp503() {
        assertThat(ErrorCode.DOCUMENT_PIPELINE_UNAVAILABLE.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private DocumentRegistrationService service(Optional<DocumentPipelineMessenger> active) {
        return new DocumentRegistrationService(registration, active);
    }

    private static void assertPipelineUnavailable(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_PIPELINE_UNAVAILABLE));
    }
}
