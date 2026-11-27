package com.shitulelv.aicollab.document.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.mapper.DocumentMapper;
import com.shitulelv.aicollab.document.infrastructure.mapper.LegacyDocumentMapper;
import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

@Repository
public class LegacyDocumentRepository extends DocumentRepository {
    private final LegacyDocumentMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final org.springframework.jdbc.core.JdbcTemplate sourceJdbc;

    public LegacyDocumentRepository(DocumentMapper mainMapper, LegacyDocumentMapper mapper,
            org.springframework.jdbc.core.JdbcTemplate sourceJdbc) {
        super(mainMapper);
        this.mapper = mapper;
        this.sourceJdbc = sourceJdbc;
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

        record ChunkEntry(UUID id, DocumentChunk chunk, String metadata, String embedding) {}
        List<ChunkEntry> entries = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            UUID chunkId = chunk.id() != null ? chunk.id() : UUID.randomUUID();
            String metadata;
            try {
                Map<String, Object> metaMap = new java.util.HashMap<>();
                metaMap.put("projectId", projectId.toString());
                metaMap.put("documentId", documentId.toString());
                metaMap.put("chunkNo", chunk.chunkNo());
                metaMap.put("filename", filename);
                metaMap.put("embeddingGeneration", generationId == null ? "LEGACY" : generationId.toString());
                if (sourceJdbc != null) {
                    var identities = sourceJdbc.queryForList("SELECT original_content_hash,parse_version,snapshot_id FROM document_body WHERE document_id=?", documentId);
                    if (!identities.isEmpty()) {
                        var identity = identities.getFirst();
                        metaMap.put("originalContentHash", identity.get("original_content_hash"));
                        metaMap.put("parseVersion", identity.get("parse_version"));
                        metaMap.put("bodySnapshotId", identity.get("snapshot_id").toString());
                    }
                }
                if (chunk.metadata() != null && !chunk.metadata().isEmpty()) {
                    metaMap.putAll(chunk.metadata());
                }
                if (chunk.parentChunkId() != null) {
                    metaMap.put("parentChunkId", chunk.parentChunkId().toString());
                }
                if (chunk.chunkType() != null) {
                    metaMap.put("chunkType", chunk.chunkType());
                }
                metadata = objectMapper.writeValueAsString(metaMap);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("文档分块元数据序列化失败", exception);
            }
            entries.add(new ChunkEntry(chunkId, chunk, metadata, embeddings.get(i).toString()));
        }

        // 按照外键依赖拓扑顺序分层插入：先插无 parent 的根分块，再插子分块（支持 PARENT -> CHILD -> SENTENCE 等多层级）
        java.util.Set<UUID> insertedIds = new java.util.HashSet<>();
        List<ChunkEntry> remaining = new ArrayList<>(entries);

        // 第 1 轮：无外键引用的根节点（例如 PARENT 块或独立的 STANDARD 块）
        java.util.Iterator<ChunkEntry> iter = remaining.iterator();
        while (iter.hasNext()) {
            ChunkEntry entry = iter.next();
            if (entry.chunk.parentChunkId() == null) {
                if (mapper.insertChunk(entry.id, projectId, documentId, entry.chunk, entry.metadata,
                        provider, model, dimension,
                        fingerprint, entry.embedding, generationId) != 1) {
                    throw new IllegalStateException("文档根分块写入失败: " + entry.id);
                }
                insertedIds.add(entry.id);
                iter.remove();
            }
        }

        // 第 2+ 轮：按外键拓扑依赖依次插入依赖项（例如 CHILD 块、SENTENCE 候选块）
        while (!remaining.isEmpty()) {
            boolean progress = false;
            iter = remaining.iterator();
            while (iter.hasNext()) {
                ChunkEntry entry = iter.next();
                if (entry.chunk.parentChunkId() == null || insertedIds.contains(entry.chunk.parentChunkId())) {
                    if (mapper.insertChunk(entry.id, projectId, documentId, entry.chunk, entry.metadata,
                            provider, model, dimension,
                            fingerprint, entry.embedding, generationId) != 1) {
                        throw new IllegalStateException("文档子分块写入失败: " + entry.id);
                    }
                    insertedIds.add(entry.id);
                    iter.remove();
                    progress = true;
                }
            }
            if (!progress) {
                // 若出现未知外部依赖或孤儿节点，兜底插入剩余节点
                for (ChunkEntry entry : remaining) {
                    mapper.insertChunk(entry.id, projectId, documentId, entry.chunk, entry.metadata,
                            provider, model, dimension,
                            fingerprint, entry.embedding, generationId);
                }
                break;
            }
        }
    }

    public List<DocumentSearchHit> search(UUID projectId, List<Double> embedding,
                                          String provider, String model, int dimension,
                                          String fingerprint, List<UUID> documentIds, int topK) {
        return search(projectId, embedding, provider, model,
                dimension, fingerprint, documentIds, topK, null, null);
    }

    public List<DocumentSearchHit> search(UUID projectId, List<Double> embedding,
            String provider, String model, int dimension, String fingerprint, List<UUID> documentIds, int topK, UUID generationId) {
        return search(projectId, embedding, provider, model, dimension, fingerprint, documentIds, topK, generationId, null);
    }

    public List<DocumentSearchHit> search(UUID projectId, List<Double> embedding,
            String provider, String model, int dimension, String fingerprint, List<UUID> documentIds, int topK, UUID generationId, DocumentFilterCriteria criteria) {
        return mapper.search(projectId, embedding.toString(), provider, model, dimension, fingerprint, documentIds, topK, generationId, criteria);
    }

    public List<DocumentSearchHit> searchLexical(UUID projectId, String query, List<UUID> documentIds, int topK, UUID generationId) {
        return searchLexical(projectId, query, documentIds, topK, generationId, null);
    }

    public List<DocumentSearchHit> searchLexical(UUID projectId, String query, List<UUID> documentIds, int topK, UUID generationId, DocumentFilterCriteria criteria) {
        return mapper.searchLexical(projectId, query, documentIds, topK, generationId, criteria);
    }

    public Optional<DocumentSearchHit> findChunkById(UUID projectId, UUID chunkId) {
        return mapper.findChunkById(projectId, chunkId);
    }

    public boolean hasChunksWithOtherFingerprint(String fingerprint) {
        return mapper.countChunksWithOtherFingerprint(fingerprint) > 0;
    }

    public long countChunksWithOtherFingerprint(String fingerprint) {
        return mapper.countChunksWithOtherFingerprint(fingerprint);
    }

}
