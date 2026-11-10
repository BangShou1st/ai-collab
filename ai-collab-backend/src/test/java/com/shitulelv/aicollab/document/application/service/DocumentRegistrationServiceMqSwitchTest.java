package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档受理分流测试：MQ 总闸语义。
 * true = 事务消息路径（注册事务作为 MQ 本地事务，事件不发布）；false = 回落进程内路径。
 */
class DocumentRegistrationServiceMqSwitchTest {

    private final DocumentRegistrationTransaction registration = mock(DocumentRegistrationTransaction.class);
    private final DocumentPipelineMessenger messenger = mock(DocumentPipelineMessenger.class);
    private final DocumentRegistrationService service =
            new DocumentRegistrationService(registration, messenger);

    private DocumentEntity document;

    @BeforeEach
    void setUp() {
        document = new DocumentEntity();
        document.setId(UUID.randomUUID());
        document.setProjectId(UUID.randomUUID());
        document.setStatus(DocumentStatus.UPLOADED);
    }

    @Test
    void mqEnabledSendsRegistrationTransactionWithoutAfterCommitEvent() {
        when(messenger.enabled()).thenReturn(true);
        // 模拟事务监听器执行本地事务（注册事务在监听器线程内同步运行）
        when(messenger.trySendRegistrationTransaction(eq(document), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });

        service.registerUploadedDocument(document);

        // 注册事务经监听器执行且不发布 AFTER_COMMIT 事件（事务消息本身即派发通知）
        verify(registration).register(document, false);
        verify(registration, never()).register(document, true);
    }

    @Test
    void mqSendFailureFallsBackToLocalRegistrationWithEvent() {
        when(messenger.enabled()).thenReturn(true);
        when(messenger.trySendRegistrationTransaction(eq(document), any())).thenReturn(false);

        service.registerUploadedDocument(document);

        // 半消息发送失败：回落注册 + 事件进程内派发；状态行是事实源，恢复 Job 兜底
        verify(registration).register(document, true);
    }

    @Test
    void mqDisabledFallsBackToLocalRegistrationWithEvent() {
        when(messenger.enabled()).thenReturn(false);

        service.registerUploadedDocument(document);

        verify(messenger, never()).trySendRegistrationTransaction(any(), any());
        verify(registration).register(document, true);
    }

    @Test
    void registrationBusinessErrorPropagatesOnMqPath() {
        when(messenger.enabled()).thenReturn(true);
        when(messenger.trySendRegistrationTransaction(eq(document), any()))
                .thenThrow(new com.shitulelv.aicollab.common.exception.BusinessException(
                        com.shitulelv.aicollab.common.exception.ErrorCode.DOCUMENT_LIMIT_EXCEEDED));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.registerUploadedDocument(document))
                .isInstanceOfSatisfying(com.shitulelv.aicollab.common.exception.BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(com.shitulelv.aicollab.common.exception.ErrorCode.DOCUMENT_LIMIT_EXCEEDED));

        // 业务异常透传，不再走回落注册（事务已回滚，避免重复创建文档行）
        verify(registration, never()).register(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
