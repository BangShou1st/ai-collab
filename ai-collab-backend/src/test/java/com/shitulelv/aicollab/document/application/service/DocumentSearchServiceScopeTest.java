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
    void mentionsAndComparisonsDoNotCreateHardSourceScope() {
        UUID project = UUID.randomUUID();
        DocumentEntity a = document(project, UUID.randomUUID(), "甲规范.md", "甲规范", DocumentStatus.READY);
        DocumentEntity b = document(project, UUID.randomUUID(), "乙规范.md", "乙规范", DocumentStatus.READY);
        DocumentSearchService service = service(project, a, b);

        assertThat(service.resolveUserDocumentScope(project, "请介绍《甲规范》").status())
                .isEqualTo(DocumentSearchService.ScopeStatus.UNRESTRICTED);
        assertThat(service.resolveUserDocumentScope(project, "根据《甲规范》回答这个问题").status())
                .isEqualTo(DocumentSearchService.ScopeStatus.UNRESTRICTED);
        assertThat(service.resolveUserDocumentScope(project, "比较《甲规范》和《乙规范》").status())
                .isEqualTo(DocumentSearchService.ScopeStatus.UNRESTRICTED);
        assertThat(service.resolveUserDocumentScope(project, "只比较《甲规范》和《乙规范》").status())
                .isEqualTo(DocumentSearchService.ScopeStatus.UNRESTRICTED);
    }

    @Test
    void explicitOnlySourceListResolvesEveryNamedDocument() {
        UUID project = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        UUID cId = UUID.randomUUID();
        DocumentSearchService service = service(project,
                document(project, aId, "甲规范.md", "甲规范", DocumentStatus.READY),
                document(project, bId, "乙规范.md", "乙规范", DocumentStatus.READY),
                document(project, cId, "丙规范.md", "丙规范", DocumentStatus.READY));

        var scope = service.resolveUserDocumentScope(project, "只能依据《甲规范》和《乙规范》回答");

        assertThat(scope.status()).isEqualTo(DocumentSearchService.ScopeStatus.ENFORCED);
        assertThat(scope.includedNames()).containsExactly("甲规范", "乙规范");
        assertThat(scope.effectiveIncludedDocumentIds()).containsExactly(aId, bId);
        assertThat(scope.allowsDocument(aId)).isTrue();
        assertThat(scope.allowsDocument(bId)).isTrue();
        assertThat(scope.allowsDocument(cId)).isFalse();

        var alternative = service.resolveUserDocumentScope(project, "只以《甲规范》和《乙规范》作为唯一依据");
        assertThat(alternative.status()).isEqualTo(DocumentSearchService.ScopeStatus.ENFORCED);
        assertThat(alternative.effectiveIncludedDocumentIds()).containsExactly(aId, bId);
    }

    @Test
    void oneMissingDocumentMakesMultiDocumentScopeIncompleteAndFailsClosed() {
        UUID project = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        DocumentSearchService service = service(project,
                document(project, aId, "甲规范.md", "甲规范", DocumentStatus.READY));

        var scope = service.resolveUserDocumentScope(project, "只允许引用《甲规范》和《不存在的规范》");

        assertThat(scope.status()).isEqualTo(DocumentSearchService.ScopeStatus.UNRESOLVED_INCOMPLETE);
        assertThat(scope.includedDocumentIds()).containsExactly(aId);
        assertThat(scope.unresolvedNames()).containsExactly("不存在的规范");
        assertThat(scope.allowsDocument(aId)).isFalse();
    }

    @Test
    void explicitExclusionBlocksOnlyTheNamedDocument() {
        UUID project = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        DocumentSearchService service = service(project,
                document(project, aId, "甲规范.md", "甲规范", DocumentStatus.READY),
                document(project, bId, "乙规范.md", "乙规范", DocumentStatus.READY));

        var scope = service.resolveUserDocumentScope(project, "比较《甲规范》和《乙规范》，但排除《乙规范》");

        assertThat(scope.status()).isEqualTo(DocumentSearchService.ScopeStatus.ENFORCED);
        assertThat(scope.includedNames()).isEmpty();
        assertThat(scope.excludedNames()).containsExactly("乙规范");
        assertThat(scope.allowsDocument(aId)).isTrue();
        assertThat(scope.allowsDocument(bId)).isFalse();
    }

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

    private static DocumentSearchService service(UUID project, DocumentEntity... documents) {
        DocumentRepository repository = mock(DocumentRepository.class);
        when(repository.list(project)).thenReturn(List.of(documents));
        return new DocumentSearchService(repository, mock(ProjectEmbeddingGateway.class));
    }
}
