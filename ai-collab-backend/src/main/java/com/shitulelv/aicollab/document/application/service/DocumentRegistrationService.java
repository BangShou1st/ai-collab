package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 文档受理入口：上传受理只走 RocketMQ 事务消息（设计 2026-11-25 §9）。
 *
 * <p>半消息先发，注册事务作为 MQ 本地事务在事务监听器内执行，提交后消息才对消费者
 * 可见；应用崩溃时由 broker 回查，以文档状态行为事实源裁决。</p>
 *
 * <p>事务消息发不出去（broker 不可用），或 MQ 被运维紧急关闭（{@code MQ_ENABLED=false}，
 * 未装配消息门面）时，注册事务不执行，接口返回 503
 * （{@link ErrorCode#DOCUMENT_PIPELINE_UNAVAILABLE}），不在进程内注册或解析。</p>
 */
@Service
public class DocumentRegistrationService {
    private static final Logger log = LoggerFactory.getLogger(DocumentRegistrationService.class);

    private final DocumentRegistrationTransaction registration;
    private final Optional<DocumentPipelineMessenger> messenger;

    public DocumentRegistrationService(DocumentRegistrationTransaction registration,
                                       Optional<DocumentPipelineMessenger> messenger) {
        this.registration = registration;
        this.messenger = messenger;
    }

    public void registerUploadedDocument(DocumentEntity document) {
        DocumentPipelineMessenger active = messenger.orElse(null);
        if (active == null) {
            log.warn("文档链路 MQ 已关闭，拒绝受理上传，documentId={}", document.getId());
            throw new BusinessException(ErrorCode.DOCUMENT_PIPELINE_UNAVAILABLE);
        }
        // 注册事务作为 MQ 本地事务执行；事务消息本身即处理通知
        if (!active.trySendRegistrationTransaction(document, () -> registration.register(document))) {
            log.warn("文档事务消息发送失败，拒绝受理上传，documentId={}", document.getId());
            throw new BusinessException(ErrorCode.DOCUMENT_PIPELINE_UNAVAILABLE);
        }
    }
}
