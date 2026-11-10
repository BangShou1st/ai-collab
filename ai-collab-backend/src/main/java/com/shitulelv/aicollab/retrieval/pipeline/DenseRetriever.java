package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于 pgvector 向量索引的密集向量检索器。
 * 支持 generationId 与向量模型指纹一致性。
 */
@Component
public class DenseRetriever implements Retriever {

    private final DocumentRepository documentRepository;
    private final ProjectEmbeddingGateway embeddingGateway;

    public DenseRetriever(DocumentRepository documentRepository, ProjectEmbeddingGateway embeddingGateway) {
        this.documentRepository = documentRepository;
        this.embeddingGateway = embeddingGateway;
    }

    @Override
    public List<RetrievalCandidate> retrieve(UUID projectId, String query, List<UUID> documentIds, int topK) {
        return retrieve(projectId, query, documentIds, topK, (DocumentFilterCriteria) null);
    }

    @Override
    public List<RetrievalCandidate> retrieve(UUID projectId, String query, List<UUID> documentIds, int topK, DocumentFilterCriteria criteria) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }

        String normalizedQuery = query.trim();
        EmbeddingBatch batch = embeddingGateway.embed(projectId, List.of(normalizedQuery), EmbeddingProgressListener.NONE,
                com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingTaskType.QUERY);
        if (batch == null || batch.vectors().isEmpty()) {
            return List.of();
        }

        List<DocumentSearchHit> hits;
        if (batch.generationId() != null) {
            hits = documentRepository.search(
                    projectId,
                    batch.vectors().getFirst(),
                    batch.provider(),
                    batch.model(),
                    batch.dimension(),
                    batch.fingerprint(),
                    documentIds,
                    topK,
                    batch.generationId(),
                    criteria
            );
        } else {
            hits = documentRepository.search(
                    projectId,
                    batch.vectors().getFirst(),
                    batch.provider(),
                    batch.model(),
                    batch.dimension(),
                    batch.fingerprint(),
                    documentIds,
                    topK,
                    null,
                    criteria
            );
        }

        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<RetrievalCandidate> candidates = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            candidates.add(RetrievalCandidate.fromDenseHit(hits.get(i), i + 1));
        }
        return candidates;
    }
}
