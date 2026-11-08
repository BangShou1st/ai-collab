package com.shitulelv.aicollab.document.application.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 文档注册/重试/批量重建索引事务提交后的处理通知（M1 迁移后按 MQ 总闸分流）。
 *
 * <p>MQ 模式：发送普通消息（这些场景的事务已提交，无需半消息一致性保障）；
 * 发送失败仅记录指标与告警日志 —— 状态行仍是 UPLOADED，恢复 Job 对账兜底。</p>
 *
 * <p>回落模式：与迁移前行为一致 —— 进程内线程池派发。</p>
 */
@Component
public class DocumentProcessingListener {
    private final DocumentTaskDispatcher dispatcher;
    private final DocumentPipelineMessenger messenger;

    public DocumentProcessingListener(DocumentTaskDispatcher dispatcher,
                                      DocumentPipelineMessenger messenger) {
        this.dispatcher = dispatcher;
        this.messenger = messenger;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterUpload(DocumentUploadedEvent event) {
        if (messenger.enabled()) {
            messenger.sendProcessingNotification(event.projectId(), event.documentId());
            return;
        }
        dispatcher.dispatch(event.projectId(), event.documentId());
    }
}
