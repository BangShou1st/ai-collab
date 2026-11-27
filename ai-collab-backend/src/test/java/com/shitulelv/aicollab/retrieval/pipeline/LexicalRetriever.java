package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.repository.LegacyDocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfig;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository;
import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于 PostgreSQL pg_trgm GIN 索引的词项/字级 n-gram 检索器。
 */
@org.springframework.context.annotation.Profile("legacy-rag-fixtures")
@Component
public class LexicalRetriever implements Retriever {

    private final LegacyDocumentRepository documentRepository;
    private final SystemEmbeddingConfigRepository configRepository;

    public LexicalRetriever(LegacyDocumentRepository documentRepository) {
        this(documentRepository, null);
    }

    @Autowired
    public LexicalRetriever(LegacyDocumentRepository documentRepository,
                            @Autowired(required = false) SystemEmbeddingConfigRepository configRepository) {
        this.documentRepository = documentRepository;
        this.configRepository = configRepository;
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
                generationId,
                criteria
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
