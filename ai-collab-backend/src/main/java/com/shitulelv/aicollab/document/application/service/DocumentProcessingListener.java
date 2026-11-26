package com.shitulelv.aicollab.document.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * 重试 / 批量重建索引事务提交后的处理通知：发送 RocketMQ 普通消息
 * （事务已提交，无需半消息一致性保障）。
 *
 * <p>发送失败或 MQ 被运维紧急关闭时不在进程内执行：文档保持 UPLOADED，
 * 由 {@link DocumentRecoveryService} 对账。</p>
 */
@Component
public class DocumentProcessingListener {
    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingListener.class);

    private final Optional<DocumentPipelineMessenger> messenger;

    public DocumentProcessingListener(Optional<DocumentPipelineMessenger> messenger) {
        this.messenger = messenger;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterUpload(DocumentUploadedEvent event) {
        messenger.ifPresentOrElse(
                active -> active.sendProcessingNotification(event.projectId(), event.documentId()),
                () -> log.warn("文档链路 MQ 已关闭，处理通知未发送，等待恢复 Job 对账，projectId={}，documentId={}",
                        event.projectId(), event.documentId()));
    }
}
