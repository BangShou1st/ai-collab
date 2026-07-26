package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingGateway;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class DocumentSearchService {
    private static final int MAX_QUERY_CODE_POINTS = 2000;
    private final DocumentRepository documents;
    private final EmbeddingGateway embeddings;

    public DocumentSearchService(DocumentRepository documents, EmbeddingGateway embeddings) {
        this.documents = documents;
        this.embeddings = embeddings;
    }

    public List<DocumentSearchHit> search(UUID projectId, String query,
                                          List<UUID> documentIds, int topK) {
        String normalized = validateQuery(query);
        if (topK < 1 || topK > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "topK 必须在 1 到 20 之间");
        }
        List<UUID> scopedIds = documentIds == null
                ? List.of() : documentIds.stream().distinct().toList();
        if (!scopedIds.isEmpty()) {
            if (documents.countDocuments(projectId, scopedIds) != scopedIds.size()) {
                throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
            }
            if (documents.countReadyDocuments(projectId, scopedIds) != scopedIds.size()) {
                throw new BusinessException(ErrorCode.DOCUMENT_NOT_READY);
            }
        }
        EmbeddingBatch queryEmbedding = embeddings.embed(List.of(normalized));
        if (queryEmbedding.vectors().size() != 1) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMBEDDING_FAILED);
        }
        return documents.search(projectId, queryEmbedding.vectors().getFirst(),
                queryEmbedding.provider(), queryEmbedding.model(), queryEmbedding.dimension(),
                scopedIds, topK);
    }

    private static String validateQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索内容不能为空");
        }
        String normalized = query.trim();
        if (normalized.codePointCount(0, normalized.length()) > MAX_QUERY_CODE_POINTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索内容不能超过 2000 个字符");
        }
        return normalized;
    }
}
