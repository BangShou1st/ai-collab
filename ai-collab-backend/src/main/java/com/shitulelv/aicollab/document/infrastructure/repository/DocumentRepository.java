package com.shitulelv.aicollab.document.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.mapper.DocumentMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

@Repository
public class DocumentRepository {
    private final DocumentMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    private com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository configs;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate sourceJdbc;
    public DocumentRepository(DocumentMapper mapper) {
        this.mapper = mapper;
    }
    public List<DocumentEntity> list(UUID projectId) { return mapper.listScoped(projectId); }
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
    public boolean markDeleting(UUID projectId, UUID documentId) {
        return mapper.markDeleting(projectId, documentId) == 1;
    }
    public void replaceChunks(UUID projectId, UUID documentId, List<DocumentChunk> chunks,
                              List<List<Double>> embeddings, String provider, String model,
                              int dimension, String filename) {
        replaceChunks(projectId, documentId, chunks, embeddings, provider, model, dimension, filename,
                com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints.fingerprint(provider, model, dimension), null);
    }
    public void replaceChunks(UUID projectId, UUID documentId, List<DocumentChunk> chunks,
                              List<List<Double>> embeddings, String provider, String model,
                              int dimension, String filename, String fingerprint, UUID generationId) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("文档分块与向量数量不一致");
        }
        mapper.deleteChunks(projectId, documentId);
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            String metadata;
            try {
                Map<String, Object> metaMap = new java.util.HashMap<>();
                metaMap.put("projectId", projectId.toString());
                metaMap.put("documentId", documentId.toString());
                metaMap.put("chunkNo", chunk.chunkNo());
                metaMap.put("filename", filename);
                metaMap.put("embeddingGeneration",generationId==null ? "LEGACY" : generationId.toString());
                if(sourceJdbc!=null) {
                    var identities=sourceJdbc.queryForList("SELECT original_content_hash,parse_version,snapshot_id FROM document_body WHERE document_id=?",documentId);
                    if(!identities.isEmpty()) {
                        var identity=identities.getFirst(); metaMap.put("originalContentHash",identity.get("original_content_hash"));
                        metaMap.put("parseVersion",identity.get("parse_version"));metaMap.put("bodySnapshotId",identity.get("snapshot_id").toString());
                    }
                }
                if (chunk.metadata() != null && !chunk.metadata().isEmpty()) {
                    metaMap.putAll(chunk.metadata());
                }
                metadata = objectMapper.writeValueAsString(metaMap);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("文档分块元数据序列化失败", exception);
            }
            if (mapper.insertChunk(UUID.randomUUID(), projectId, documentId, chunk, metadata,
                    provider, model, dimension,
                    fingerprint, embeddings.get(i).toString(), generationId) != 1) {
                throw new IllegalStateException("文档分块写入失败");
            }
        }
    }
    public Optional<DocumentProcessingAttempt> lockAttempt(UUID projectId, UUID documentId) {
        return mapper.lockAttempt(projectId, documentId);
    }
    public boolean deleteRows(UUID projectId, UUID documentId) {
        mapper.deleteChunks(projectId, documentId);
        return mapper.deleteDocument(projectId, documentId) == 1;
    }
    public List<DocumentRecoveryCandidate> recoverable(OffsetDateTime before) { return mapper.recoverable(before); }
    public boolean failStale(DocumentRecoveryCandidate candidate, OffsetDateTime before) {
        return mapper.failStale(candidate.projectId(), candidate.documentId(), candidate.status(),
                candidate.processingToken(), before) == 1;
    }
    public List<DocumentSearchHit> search(UUID projectId, List<Double> embedding,
                                          String provider, String model, int dimension,
                                          String fingerprint, List<UUID> documentIds, int topK) {
        return mapper.search(projectId, embedding.toString(), provider, model,
                dimension, fingerprint, documentIds, topK, null);
    }
    public List<DocumentSearchHit> search(UUID projectId, List<Double> embedding,
            String provider, String model, int dimension, String fingerprint, List<UUID> documentIds, int topK, UUID generationId) {
        return mapper.search(projectId, embedding.toString(), provider, model, dimension, fingerprint, documentIds, topK, generationId);
    }
    public boolean hasChunksWithOtherFingerprint(String fingerprint) {
        return mapper.countChunksWithOtherFingerprint(fingerprint) > 0;
    }
    public long countChunksWithOtherFingerprint(String fingerprint) {
        return mapper.countChunksWithOtherFingerprint(fingerprint);
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
