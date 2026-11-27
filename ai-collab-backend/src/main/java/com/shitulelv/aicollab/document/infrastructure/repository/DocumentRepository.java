package com.shitulelv.aicollab.document.infrastructure.repository;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.mapper.DocumentMapper;
import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@org.springframework.context.annotation.Primary
public class DocumentRepository {
    private final DocumentMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository configs;

    public DocumentRepository(DocumentMapper mapper) {
        this.mapper = mapper;
    }

    public List<DocumentEntity> list(UUID projectId) { return mapper.listScoped(projectId); }

    public int countReadyForIndex() { return mapper.countReadyForIndex(); }

    public List<DocumentEntity> listVisibleForRetrieval(UUID projectId, List<UUID> requested,
            List<UUID> planned, DocumentFilterCriteria criteria, int limit) {
        return mapper.listVisibleForRetrieval(projectId, requested, planned, criteria, limit);
    }
    public Optional<DocumentEntity> find(UUID projectId, UUID documentId) {
        return mapper.findScoped(projectId, documentId);
    }
    public int countActive(UUID projectId) { return mapper.countActive(projectId); }

    @org.springframework.transaction.annotation.Transactional
    public void create(DocumentEntity entity) {
        if (configs != null) configs.lockInfrastructure();
        mapper.insert(entity);
    }

    public boolean claim(UUID projectId, UUID documentId, UUID processingToken) {
        return mapper.claim(projectId, documentId, processingToken) == 1;
    }

    public boolean heartbeat(UUID projectId, UUID documentId, UUID processingToken, DocumentStatus status) {
        return mapper.heartbeat(projectId, documentId, processingToken, status) == 1;
    }

    public boolean markIndexing(UUID projectId, UUID documentId, UUID processingToken, String parserType) {
        return mapper.markIndexing(projectId, documentId, processingToken, parserType) == 1;
    }

    public boolean markReady(UUID projectId, UUID documentId, UUID processingToken,
                             int count, String provider, String model, int dimension) {
        return markReady(projectId, documentId, processingToken, count, provider, model, dimension,
                com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints.fingerprint(provider, model, dimension));
    }

    public boolean markReady(UUID projectId, UUID documentId, UUID processingToken,
                             int count, String provider, String model, int dimension, String fingerprint) {
        return mapper.markReady(projectId, documentId, processingToken,
                count, provider, model, dimension,
                fingerprint) == 1;
    }

    public boolean markFailed(UUID projectId, UUID documentId, UUID processingToken, String message) {
        return mapper.markFailed(projectId, documentId, processingToken, message) == 1;
    }

    public boolean resetFailed(UUID projectId, UUID documentId) {
        return mapper.resetFailed(projectId, documentId) == 1;
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean resetForReindex(UUID projectId, UUID documentId) {
        if (configs != null) configs.lockInfrastructure();
        return mapper.resetForReindex(projectId, documentId) == 1;
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean resetForReindexWithSource(UUID projectId, UUID documentId, String objectKey, long sizeBytes) {
        if (configs != null) configs.lockInfrastructure();
        return mapper.resetForReindexWithSource(projectId, documentId, objectKey, sizeBytes) == 1;
    }

    public boolean markDeleting(UUID projectId, UUID documentId) {
        return mapper.markDeleting(projectId, documentId) == 1;
    }

    public Optional<DocumentProcessingAttempt> lockAttempt(UUID projectId, UUID documentId) {
        return mapper.lockAttempt(projectId, documentId);
    }

    public boolean deleteRows(UUID projectId, UUID documentId) {
        return mapper.deleteDocument(projectId, documentId) == 1;
    }

    public List<DocumentRecoveryCandidate> recoverable(OffsetDateTime before) { return mapper.recoverable(before); }

    public boolean failStale(DocumentRecoveryCandidate candidate, OffsetDateTime before) {
        return mapper.failStale(candidate.projectId(), candidate.documentId(), candidate.status(),
                candidate.processingToken(), before) == 1;
    }

    public List<UUID> projectIdsWithReadyDocuments() {
        return mapper.projectIdsWithReadyDocuments();
    }

    public int countReadyDocuments(UUID projectId, List<UUID> documentIds) {
        return mapper.countReadyDocuments(projectId, documentIds);
    }

    public int countDocuments(UUID projectId, List<UUID> documentIds) {
        return mapper.countDocuments(projectId, documentIds);
    }
}
