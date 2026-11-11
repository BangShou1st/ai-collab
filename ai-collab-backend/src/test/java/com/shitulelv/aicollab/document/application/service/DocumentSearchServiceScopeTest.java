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
    void scopeResolutionReportsOutcomeToMetricsWithoutChangingResult() {
        UUID project = UUID.randomUUID();
        DocumentEntity a = document(project, UUID.randomUUID(), "甲规范.md", "甲规范", DocumentStatus.READY);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        DocumentSearchService service = service(project, a);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "scopeMetrics",
                new com.shitulelv.aicollab.document.infrastructure.diagnostics.DocumentScopeMetrics(registry));

        try {
            // 有范围 → ENFORCED；未解析 → UNRESOLVED_INCOMPLETE；无范围 → UNRESTRICTED
            assertThat(service.resolveUserDocumentScope(project, "只能依据《甲规范》回答").status())
                    .isEqualTo(DocumentSearchService.ScopeStatus.ENFORCED);
            assertThat(service.resolveUserDocumentScope(project, "只允许引用《不存在的规范》").status())
                    .isEqualTo(DocumentSearchService.ScopeStatus.UNRESOLVED_INCOMPLETE);
            assertThat(service.resolveUserDocumentScope(project, "介绍一下项目资料").status())
                    .isEqualTo(DocumentSearchService.ScopeStatus.UNRESTRICTED);

            String counter = com.shitulelv.aicollab.document.infrastructure.diagnostics.DocumentScopeMetrics.COUNTER;
            assertThat(registry.get(counter).tag("status", "ENFORCED").counter().count()).isEqualTo(1);
            assertThat(registry.get(counter).tag("status", "UNRESOLVED_INCOMPLETE").counter().count()).isEqualTo(1);
            assertThat(registry.get(counter).tag("status", "UNRESTRICTED").counter().count()).isEqualTo(1);

            // 指标故障不得改变解析结果
            var broken = mock(io.micrometer.core.instrument.MeterRegistry.class);
            when(broken.counter(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(String[].class)))
                    .thenThrow(new IllegalStateException("metrics unavailable"));
            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "scopeMetrics",
                    new com.shitulelv.aicollab.document.infrastructure.diagnostics.DocumentScopeMetrics(broken));
            assertThat(service.resolveUserDocumentScope(project, "只能依据《甲规范》回答").status())
                    .isEqualTo(DocumentSearchService.ScopeStatus.ENFORCED);
        } finally {
            registry.close();
        }
    }

    @Test
    void sameNamedDocumentsStayUnresolvedAndFailClosed() {
        UUID project = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID unique = UUID.randomUUID();
        // 两份 READY 文档归一化后同名（不同目录、不同 id）：无法唯一确定用户指的是哪一份
        DocumentSearchService service = service(project,
                document(project, first, "2025/规范.md", "规范", DocumentStatus.READY),
                document(project, second, "规范.md", "规范", DocumentStatus.READY),
                document(project, unique, "甲规范.md", "甲规范", DocumentStatus.READY));

        var scope = service.resolveUserDocumentScope(project, "只根据《规范》和《甲规范》回答");

        // 同名 → unresolved（DocumentSearchService#matchesName 匹配数 != 1），整体 fail-closed
        assertThat(scope.status()).isEqualTo(DocumentSearchService.ScopeStatus.UNRESOLVED_INCOMPLETE);
        assertThat(scope.unresolvedNames()).containsExactly("规范");
        assertThat(scope.canSearch()).isFalse();
        assertThat(scope.allowsDocument(first)).isFalse();
        assertThat(scope.allowsDocument(second)).isFalse();
        // 唯一命中的名字仍被解析出来，但不得据此放行（不静默挑一份、也不退回全项目检索）
        assertThat(scope.includedDocumentIds()).containsExactly(unique);

        // 对照：同一个项目里没有范围限定的请求不受影响，仍走全项目检索
        assertThat(service.resolveUserDocumentScope(project, "介绍一下项目资料").status())
                .isEqualTo(DocumentSearchService.ScopeStatus.UNRESTRICTED);
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
