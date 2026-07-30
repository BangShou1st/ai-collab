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

import java.util.Map;

@Service
public class DocumentRegistrationService {
    private static final int MAX_DOCUMENTS = 100;
    private final ProjectRepository projects;
    private final DocumentRepository documents;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public DocumentRegistrationService(ProjectRepository projects, DocumentRepository documents,
                                       AuditService audit, ApplicationEventPublisher events) {
        this.projects = projects;
        this.documents = documents;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public void registerUploadedDocument(DocumentEntity document) {
        ProjectStatus status = projects.lockStatus(document.getProjectId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND));
        ProjectWritePolicy.requireWritable(status);
        if (documents.countActive(document.getProjectId()) >= MAX_DOCUMENTS) {
            throw new BusinessException(ErrorCode.DOCUMENT_LIMIT_EXCEEDED);
        }
        documents.create(document);
        audit.write(document.getProjectId(), document.getUploadedBy(),
                "DOCUMENT_UPLOADED", "PROJECT_DOCUMENT", document.getId(),
                Map.of("originalFilename", document.getOriginalFilename()));
        events.publishEvent(new DocumentUploadedEvent(document.getProjectId(), document.getId()));
    }
}
