package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DocumentRetryService {
    private final DocumentRepository documents;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public DocumentRetryService(DocumentRepository documents, AuditService audit,
                                ApplicationEventPublisher events) {
        this.documents = documents;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public void request(UUID projectId, UUID documentId, UUID userId) {
        if (!documents.resetFailed(projectId, documentId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
        }
        audit.write(projectId, userId,
                "DOCUMENT_RETRY_REQUESTED", "PROJECT_DOCUMENT", documentId);
        events.publishEvent(new DocumentUploadedEvent(projectId, documentId));
    }
}
