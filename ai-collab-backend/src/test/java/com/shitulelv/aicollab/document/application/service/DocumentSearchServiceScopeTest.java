package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentSearchServiceScopeTest {
    @Test
    void resolvesExactCurrentProjectDocumentNamesWithoutPartialGuessing() {
        UUID project = UUID.randomUUID();
        UUID exactId = UUID.randomUUID();
        UUID similarId = UUID.randomUUID();
        DocumentEntity exact = document(project, exactId, "批量导出规范.md", "批量导出规范", DocumentStatus.READY);
        DocumentEntity similar = document(project, similarId, "批量导出规范-旧版.md", "批量导出规范-旧版",
                DocumentStatus.READY);
        DocumentRepository repository = mock(DocumentRepository.class);
        when(repository.list(project)).thenReturn(List.of(exact, similar));
        DocumentSearchService service = new DocumentSearchService(repository, mock(ProjectEmbeddingGateway.class));

        assertThat(service.resolveNamedDocumentIds(project, List.of("批量导出规范")))
                .containsExactly(exactId);
        assertThat(service.resolveNamedDocumentIds(project, List.of("批量导出")))
                .isEmpty();
    }

    @Test
    void nonReadyDocumentsCannotBeResolvedAsSearchScope() {
        UUID project = UUID.randomUUID();
        UUID indexingId = UUID.randomUUID();
        DocumentEntity indexing = document(project, indexingId, "批量导出规范.md", "批量导出规范",
                DocumentStatus.INDEXING);
        DocumentRepository repository = mock(DocumentRepository.class);
        when(repository.list(project)).thenReturn(List.of(indexing));
        DocumentSearchService service = new DocumentSearchService(repository, mock(ProjectEmbeddingGateway.class));

        assertThat(service.resolveNamedDocumentIds(project, List.of("批量导出规范"))).isEmpty();
    }

    private static DocumentEntity document(UUID project, UUID id, String filename, String displayName,
                                           DocumentStatus status) {
        DocumentEntity entity = new DocumentEntity();
        entity.setProjectId(project);
        entity.setId(id);
        entity.setOriginalFilename(filename);
        entity.setDisplayName(displayName);
        entity.setStatus(status);
        return entity;
    }
}
