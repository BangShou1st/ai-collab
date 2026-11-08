package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;
import com.shitulelv.aicollab.project.domain.policy.ProjectWritePolicy;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档注册事务（M1 拆分）：从 {@link DocumentRegistrationService} 中拆出，
 * 使注册事务既能被进程内路径直接调用，也能作为 RocketMQ 事务消息的
 * "本地事务"在事务监听器内执行（半消息先发 → 本地事务 → 提交后消息可见）。
 */
@Service
public class DocumentRegistrationTransaction {
    /** 单项目活跃文档上限（沿用原 registerUploadedDocument 的既有语义）。 */
    static final int MAX_DOCUMENTS = 100;

    private final ProjectRepository projects;
    private final DocumentRepository documents;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public DocumentRegistrationTransaction(ProjectRepository projects, DocumentRepository documents,
                                           AuditService audit, ApplicationEventPublisher events) {
        this.projects = projects;
        this.documents = documents;
        this.audit = audit;
        this.events = events;
    }

    /**
     * 注册上传文档（独立事务）。
     *
     * @param notifyAfterCommit true 时发布 {@link DocumentUploadedEvent}，提交后由
     *                          {@link DocumentProcessingListener} 决定进程内派发或 MQ 通知；
     *                          MQ 事务消息路径传 false（事务消息本身就是派发通知，避免双重投递）
     */
    @Transactional
    public void register(DocumentEntity document, boolean notifyAfterCommit) {
        ProjectStatus status = projects.lockStatus(document.getProjectId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
        ProjectWritePolicy.requireWritable(status);
        if (documents.countActive(document.getProjectId()) >= MAX_DOCUMENTS) {
            throw new BusinessException(ErrorCode.DOCUMENT_LIMIT_EXCEEDED);
        }
        documents.create(document);
        audit.write(document.getProjectId(), document.getUploadedBy(),
                "DOCUMENT_UPLOADED", "PROJECT_DOCUMENT", document.getId(),
                java.util.Map.of("originalFilename", document.getOriginalFilename()));
        if (notifyAfterCommit) {
            events.publishEvent(new DocumentUploadedEvent(document.getProjectId(), document.getId()));
        }
    }
}
