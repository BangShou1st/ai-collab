package com.shitulelv.aicollab.retrieval.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCapabilityResolver;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.query.RetrievalStrategy;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeHitVerifier;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchClient;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchModels;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** P3 后门面语义：固定 HYBRID + 原始查询（路由随查询理解迁 Python，契约 §2.1 行 1、Q4=a）。 */
class P2RemoteProjectKnowledgeSearchTest {
    private final UUID project = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private final UUID row = UUID.randomUUID();
    private final UUID snapshot = UUID.randomUUID();
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final KnowledgeSearchClient client = mock(KnowledgeSearchClient.class);
    private final KnowledgeHitVerifier verifier = mock(KnowledgeHitVerifier.class);
    private final RerankCapabilityResolver rerank = mock(RerankCapabilityResolver.class);
    private final ProjectKnowledgeRetrieval retrieval = new ProjectKnowledgeRetrieval(
            mock(ProjectAccessGuard.class), null,
            new RemoteProjectKnowledgeSearch(documents, client, verifier, rerank, KnowledgeSearchProperties.defaults()));

    @Test
    void emptyVisibilityDoesNotCallWorkerOrResolveRerank() {
        assertThat(retrieval.retrieve(request()).hits()).isEmpty();
        verifyNoInteractions(client, verifier, rerank);
    }

    @Test
    void visibilityOverLimitFailsBeforeCallingWorker() {
        when(documents.listVisibleForRetrieval(eq(project), any(), any(), any(), eq(5001)))
                .thenReturn(IntStream.range(0, 5001).mapToObj(i -> document(new UUID(0, i))).toList());

        assertThatThrownBy(() -> retrieval.retrieve(request())).isInstanceOfSatisfying(
                BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(client, verifier);
    }

    @Test
    void fixedHybridCarriesOnlyVisibleIdsAndUsesVerifiedCanonicalContent() {
        when(documents.listVisibleForRetrieval(eq(project), eq(List.of(document)), eq(List.of()), any(), anyInt()))
                .thenReturn(List.of(document(document)));
        when(rerank.hasPublishedBinding(project)).thenReturn(true);
        var hit = new KnowledgeSearchModels.Hit(1, document, 1, "a".repeat(64), "CHILD", null,
                "worker heading", "untrusted", snapshot, Map.of(), null,
                new KnowledgeSearchModels.Scores(0.7, null, null, null, 0.7));
        when(client.search(any())).thenReturn(new KnowledgeSearchModels.Response(UUID.randomUUID(), UUID.randomUUID(),
                "HYBRID", List.of(hit), Map.of()));
        var accepted = new KnowledgeHitVerifier.VerifiedHit(hit, document, 1, hit.contentHash(), snapshot,
                "database heading", "database body", row, 3, List.of());
        when(verifier.verify(eq(project), any(), eq(List.of(hit))))
                .thenReturn(new KnowledgeHitVerifier.Outcome(List.of(accepted), Map.of()));

        assertThat(retrieval.retrieve(request()).hits()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo(row);
            assertThat(result.content()).isEqualTo("database body");
            assertThat(result.metadata()).containsEntry("bodySnapshotId", snapshot).containsEntry("chunkNo", 1);
            assertThat(result.metadata()).containsEntry("pageNumber", 3);
        });
        assertThat(retrieval.retrieve(request()).appliedStrategy()).isEqualTo(RetrievalStrategy.HYBRID);
        var capture = ArgumentCaptor.forClass(KnowledgeSearchModels.Request.class);
        verify(client, org.mockito.Mockito.times(2)).search(capture.capture());
        assertThat(capture.getValue().visibleDocumentIds()).containsExactly(document);
        // 固定 HYBRID：原始查询作为唯一 PRIMARY，无子查询、无标题过滤
        assertThat(capture.getValue().queries()).extracting(KnowledgeSearchModels.Query::text)
                .containsExactly("original");
        assertThat(capture.getValue().queries()).extracting(query -> query.role())
                .containsExactly("PRIMARY");
        assertThat(capture.getValue().rerank()).isEqualTo(KnowledgeSearchModels.Rerank.CONFIGURED);
        assertThat(capture.getValue().headingFilter().exact()).isNull();
        assertThat(capture.getValue().headingFilter().contains()).isNull();
    }

    private ProjectKnowledgeRetrieval.IntentRequest request() {
        return new ProjectKnowledgeRetrieval.IntentRequest(project, user, "original", List.of(document), 5,
                ProjectKnowledgeRetrieval.RetrievalIntent.KNOWLEDGE_QA);
    }

    private static DocumentEntity document(UUID id) {
        var entity = new DocumentEntity();
        entity.setId(id);
        entity.setOriginalFilename("test.md");
        return entity;
    }
}
