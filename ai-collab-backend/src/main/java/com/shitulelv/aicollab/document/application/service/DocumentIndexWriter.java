package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentProcessingAttempt;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DocumentIndexWriter {
    private final DocumentRepository documents;
    private final AuditService audit;
    public DocumentIndexWriter(DocumentRepository documents, AuditService audit) {
        this.documents = documents;
        this.audit = audit;
    }

    @Transactional
    public boolean replaceAndComplete(UUID projectId, UUID documentId,
                                      UUID processingToken,
                                      List<DocumentChunk> chunks, EmbeddingBatch embedding,
                                      String filename) {
        DocumentProcessingAttempt attempt = documents.lockAttempt(projectId, documentId).orElse(null);
        if (attempt == null || attempt.status() != DocumentStatus.INDEXING
                || !processingToken.equals(attempt.processingToken())) {
            return false;
        }
        if (chunks.size() != embedding.vectors().size()) {
            throw new IllegalArgumentException("文档分块与向量数量不一致");
        }
        documents.replaceChunks(projectId, documentId, chunks, embedding.vectors(),
                embedding.provider(), embedding.model(), embedding.dimension(), filename);
        if (!documents.markReady(projectId, documentId, processingToken, chunks.size(),
                embedding.provider(), embedding.model(), embedding.dimension())) {
            throw new IllegalStateException("文档状态在索引写入期间发生变化");
        }
        audit.write(projectId, attempt.uploadedBy(), "DOCUMENT_INDEXED",
                "PROJECT_DOCUMENT", documentId);
        return true;
    }
}
