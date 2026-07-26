package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRecoveryCandidate;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.OffsetDateTime;

@Service
public class DocumentFailureRecorder {
    private static final int MAX_ERROR_CODE_POINTS = 1000;
    private final DocumentRepository documents;
    private final AuditService audit;

    public DocumentFailureRecorder(DocumentRepository documents, AuditService audit) {
        this.documents = documents;
        this.audit = audit;
    }

    @Transactional
    public boolean record(UUID projectId, UUID documentId, UUID processingToken, String errorMessage) {
        DocumentEntity document = documents.find(projectId, documentId).orElse(null);
        if (document == null || !documents.markFailed(
                projectId, documentId, processingToken, truncate(errorMessage))) {
            return false;
        }
        audit.write(projectId, document.getUploadedBy(), "DOCUMENT_PROCESSING_FAILED",
                "PROJECT_DOCUMENT", documentId);
        return true;
    }

    @Transactional
    public boolean recordStale(DocumentRecoveryCandidate candidate, OffsetDateTime staleBefore) {
        DocumentEntity document = documents.find(
                candidate.projectId(), candidate.documentId()).orElse(null);
        if (document == null || !documents.failStale(candidate, staleBefore)) {
            return false;
        }
        audit.write(candidate.projectId(), document.getUploadedBy(),
                "DOCUMENT_PROCESSING_FAILED", "PROJECT_DOCUMENT", candidate.documentId());
        return true;
    }

    private static String truncate(String value) {
        String safe = value == null || value.isBlank() ? "文档处理失败，请重试" : value;
        int points = safe.codePointCount(0, safe.length());
        return points <= MAX_ERROR_CODE_POINTS
                ? safe : safe.substring(0, safe.offsetByCodePoints(0, MAX_ERROR_CODE_POINTS));
    }
}
