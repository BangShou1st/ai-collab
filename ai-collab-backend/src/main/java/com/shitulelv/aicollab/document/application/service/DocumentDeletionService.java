package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DocumentDeletionService {
    private final DocumentRepository documents;
    private final AuditService audit;

    public DocumentDeletionService(DocumentRepository documents, AuditService audit) {
        this.documents = documents;
        this.audit = audit;
    }

    @Transactional
    public boolean deleteRows(UUID projectId, UUID documentId, UUID userId) {
        boolean deleted = documents.deleteRows(projectId, documentId);
        if (deleted) {
            audit.write(projectId, userId, "DOCUMENT_DELETED", "PROJECT_DOCUMENT", documentId);
        }
        return deleted;
    }
}
