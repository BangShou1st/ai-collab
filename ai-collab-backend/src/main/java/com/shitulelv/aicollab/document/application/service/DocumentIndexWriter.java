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
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentIndexWriter {
    private final DocumentRepository documents;
    private final AuditService audit;
    private com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository configs;
    @org.springframework.beans.factory.annotation.Autowired
    void configureSnapshots(com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository configs) {
        this.configs = configs;
    }
    public DocumentIndexWriter(DocumentRepository documents, AuditService audit) {
        this.documents = documents;
        this.audit = audit;
    }

    @Transactional
    public boolean replaceAndComplete(UUID projectId, UUID documentId,
                                      UUID processingToken,
                                      List<DocumentChunk> chunks, EmbeddingBatch embedding,
                                      String filename) {
        if (configs != null) {
            configs.lockInfrastructure();
            var active = configs.findActive().orElseThrow(() -> new com.shitulelv.aicollab.common.exception.BusinessException(
                    com.shitulelv.aicollab.common.exception.ErrorCode.EMBEDDING_REINDEX_REQUIRED));
            if (!java.util.Objects.equals(active.fingerprint(), embedding.fingerprint())
                    || !java.util.Objects.equals(active.generationId(), embedding.generationId())) {
                throw new com.shitulelv.aicollab.common.exception.BusinessException(
                        com.shitulelv.aicollab.common.exception.ErrorCode.EMBEDDING_REINDEX_REQUIRED);
            }
        }
        DocumentProcessingAttempt attempt = documents.lockAttempt(projectId, documentId).orElse(null);
        if (attempt == null || attempt.status() != DocumentStatus.INDEXING
                || !processingToken.equals(attempt.processingToken())) {
            return false;
        }
        if (chunks.size() != embedding.vectors().size()) {
            throw new IllegalArgumentException("文档分块与向量数量不一致");
        }
        documents.replaceChunks(projectId, documentId, chunks, embedding.vectors(),
                embedding.provider(), embedding.model(), embedding.dimension(), filename, embedding.fingerprint(), embedding.generationId());
        if (!documents.markReady(projectId, documentId, processingToken, chunks.size(),
                embedding.provider(), embedding.model(), embedding.dimension(), embedding.fingerprint())) {
            throw new IllegalStateException("文档状态在索引写入期间发生变化");
        }
        audit.write(projectId, attempt.uploadedBy(), "DOCUMENT_INDEXED",
                "PROJECT_DOCUMENT", documentId,
                Map.of("originalFilename", filename != null ? filename : "未知文件"));
        return true;
    }
}
