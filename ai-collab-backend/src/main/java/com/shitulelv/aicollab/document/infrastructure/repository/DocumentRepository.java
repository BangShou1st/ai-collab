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
    public DocumentRepository(DocumentMapper mapper) {
        this.mapper = mapper;
    }
    public List<DocumentEntity> list(UUID projectId) { return mapper.listScoped(projectId); }
    public Optional<DocumentEntity> find(UUID projectId, UUID documentId) {
        return mapper.findScoped(projectId, documentId);
    }
    public int countActive(UUID projectId) { return mapper.countActive(projectId); }
    public void create(DocumentEntity entity) { mapper.insert(entity); }
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
        return mapper.markReady(projectId, documentId, processingToken,
                count, provider, model, dimension) == 1;
    }
    public boolean markFailed(UUID projectId, UUID documentId, UUID processingToken, String message) {
        return mapper.markFailed(projectId, documentId, processingToken, message) == 1;
    }
    public boolean resetFailed(UUID projectId, UUID documentId) {
        return mapper.resetFailed(projectId, documentId) == 1;
    }
    public boolean markDeleting(UUID projectId, UUID documentId) {
        return mapper.markDeleting(projectId, documentId) == 1;
    }
    public void replaceChunks(UUID projectId, UUID documentId, List<DocumentChunk> chunks,
                              List<List<Double>> embeddings, String provider, String model,
                              int dimension, String filename) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("文档分块与向量数量不一致");
        }
        mapper.deleteChunks(projectId, documentId);
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            String metadata;
            try {
                metadata = objectMapper.writeValueAsString(Map.of(
                        "projectId", projectId.toString(),
                        "documentId", documentId.toString(),
                        "chunkNo", chunk.chunkNo(),
                        "filename", filename));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("文档分块元数据序列化失败", exception);
            }
            if (mapper.insertChunk(UUID.randomUUID(), projectId, documentId, chunk, metadata,
                    provider, model, dimension, embeddings.get(i).toString()) != 1) {
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
                                          List<UUID> documentIds, int topK) {
        return mapper.search(projectId, embedding.toString(), provider, model,
                dimension, documentIds, topK);
    }
    public int countReadyDocuments(UUID projectId, List<UUID> documentIds) {
        return mapper.countReadyDocuments(projectId, documentIds);
    }
}
