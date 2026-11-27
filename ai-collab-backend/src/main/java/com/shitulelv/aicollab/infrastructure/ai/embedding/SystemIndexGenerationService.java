package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.internal.ai.index.IndexAdminClient;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SystemIndexGenerationService {
    private final IndexAdminClient client;
    private final DocumentRepository documents;
    private final UserService users;

    public SystemIndexGenerationService(IndexAdminClient client, DocumentRepository documents, UserService users) {
        this.client = client;
        this.documents = documents;
        this.users = users;
    }

    public record GenerationView(UUID id, String state, long total, long completed, int failed,
            String errorCode, String modelName, int dimensions) {}

    public List<GenerationView> list(UUID userId) {
        users.requireSystemAdmin(userId);
        int ready = documents.countReadyForIndex();
        return generations().stream()
                .map(generation -> new GenerationView(generation.id(), generation.status(),
                        "BUILDING".equals(generation.status()) ? Math.max(ready, generation.documentCount())
                                : generation.documentCount(),
                        generation.documentCount(), 0, null, generation.embeddingModel(), generation.dimension()))
                .toList();
    }

    public void activate(UUID userId, UUID generationId) {
        users.requireSystemAdmin(userId);
        // Only the reconciler may promote a BUILDING generation after verifying all READY documents.
        if (!"ACTIVE".equals(find(generationId).status())) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT, "索引由重建任务核对完成后自动激活");
        }
    }

    public void discard(UUID userId, UUID generationId) {
        users.requireSystemAdmin(userId);
        try {
            client.discard(generationId);
        } catch (IndexAdminClient.IndexAdminException unavailable) {
            if (unavailable.httpStatus() == 409) {
                throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT, "只能清理已退役或失败的索引");
            }
            throw new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);
        }
    }

    private IndexAdminClient.GenerationInfo find(UUID id) {
        return generations().stream().filter(item -> id.equals(item.id())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    private List<IndexAdminClient.GenerationInfo> generations() {
        try {
            return client.listGenerations();
        } catch (IndexAdminClient.IndexAdminException unavailable) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);
        }
    }
}
