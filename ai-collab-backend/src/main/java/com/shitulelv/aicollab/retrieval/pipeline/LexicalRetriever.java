package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfig;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于 PostgreSQL pg_trgm GIN 索引的词项/字级 n-gram 检索器。
 */
@Component
public class LexicalRetriever implements Retriever {

    private final DocumentRepository documentRepository;
    private final SystemEmbeddingConfigRepository configRepository;

    public LexicalRetriever(DocumentRepository documentRepository) {
        this(documentRepository, null);
    }

    @Autowired
    public LexicalRetriever(DocumentRepository documentRepository,
                            @Autowired(required = false) SystemEmbeddingConfigRepository configRepository) {
        this.documentRepository = documentRepository;
        this.configRepository = configRepository;
    }

    @Override
    public List<RetrievalCandidate> retrieve(UUID projectId, String query, List<UUID> documentIds, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }

        String normalizedQuery = query.trim();
        UUID generationId = null;
        if (configRepository != null) {
            generationId = configRepository.findActive()
                    .map(SystemEmbeddingConfig::generationId)
                    .orElse(null);
        }

        List<DocumentSearchHit> hits = documentRepository.searchLexical(
                projectId,
                normalizedQuery,
                documentIds,
                topK,
                generationId
        );

        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<RetrievalCandidate> candidates = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            candidates.add(RetrievalCandidate.fromLexicalHit(hits.get(i), i + 1));
        }
        return candidates;
    }
}
