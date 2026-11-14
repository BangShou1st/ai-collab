package com.shitulelv.aicollab.retrieval.application;

import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.query.EvidenceSufficiencyProbe;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import com.shitulelv.aicollab.retrieval.query.QueryRouteDecision;
import com.shitulelv.aicollab.retrieval.query.QueryRouter;
import com.shitulelv.aicollab.retrieval.query.StructuredQuery;
import com.shitulelv.aicollab.retrieval.query.StrategyAwareRetrievalExecutor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectKnowledgeRetrievalStrategyAwareTest {

    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final DocumentSearchService search = mock(DocumentSearchService.class);
    private final RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
    private final QueryRouter router = mock(QueryRouter.class);
    private final StrategyAwareRetrievalExecutor executor = mock(StrategyAwareRetrievalExecutor.class);
    private final ProjectKnowledgeRetrieval retrieval =
            new ProjectKnowledgeRetrieval(access, search, pipeline, router, executor);

    private final UUID project = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private final UUID otherDocument = UUID.randomUUID();

    @Test
    void strategyAwareRoutesAndDelegatesToExecutorWithEvidenceProbe() {
        MetadataFilter filter = MetadataFilter.eq("mimeType", "application/pdf");
        QueryRouteDecision decision = QueryRouteDecision.complex(
                "planned",
                StructuredQuery.of("计划改写后的查询", List.of(filter)),
                List.of("子问题一", "子问题二"),
                List.of(document));
        DocumentSearchHit hit = new DocumentSearchHit(
                UUID.randomUUID(), document, "规范.pdf", "保留策略",
                "临时区保存二十四小时。", "hash", 0.93, Map.of("pageNumber", 3));
        EvidenceSufficiencyProbe probe = candidates -> true;

        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(router.route("原始问题", List.of(document, otherDocument))).thenReturn(decision);
        when(executor.execute(project, decision, "计划改写后的查询",
                List.of(document), List.of(filter), 8, probe)).thenReturn(List.of(hit));

        ProjectKnowledgeRetrieval.Result result = retrieval.retrieve(
                new ProjectKnowledgeRetrieval.Request(
                        project, user, "原始问题", List.of(document, otherDocument), 8,
                        ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE),
                probe);

        assertThat(result.hits()).containsExactly(hit);
        assertThat(result.effectiveQuery()).isEqualTo("计划改写后的查询");
        assertThat(result.filters()).containsExactly(filter);
        assertThat(result.retrievalCore())
                .isEqualTo(ProjectKnowledgeRetrieval.RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE);
        verify(executor).execute(project, decision, "计划改写后的查询",
                List.of(document), List.of(filter), 8, probe);
        verify(search, never()).search(any(), any(), anyList(), anyInt());
    }

    @Test
    void plannedDocumentScopeCannotWidenCallerScope() {
        QueryRouteDecision decision = QueryRouteDecision.complex(
                "planned", StructuredQuery.of("q", List.of()),
                List.of("a", "b"), List.of(otherDocument, document));
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(router.route("q", List.of(document))).thenReturn(decision);
        when(executor.execute(eq(project), same(decision), eq("q"),
                eq(List.of(document)), anyList(), eq(3), any()))
                .thenReturn(List.of());

        retrieval.retrieve(new ProjectKnowledgeRetrieval.Request(
                project, user, "q", List.of(document), 3,
                ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE));

        verify(executor).execute(project, decision, "q",
                List.of(document), List.of(), 3, null);
    }

    @Test
    void missingExecutorFailsClosedOnlyWhenStrategyAwareIsRequested() {
        ProjectKnowledgeRetrieval legacyWiring =
                new ProjectKnowledgeRetrieval(access, search, pipeline, router);
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);

        assertThatThrownBy(() -> legacyWiring.retrieve(
                new ProjectKnowledgeRetrieval.Request(
                        project, user, "q", null, 3,
                        ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("StrategyAwareRetrievalExecutor");
    }

    @Test
    void nullAndDuplicatePlannedDocumentIdsAreNormalized() {
        QueryRouteDecision decision = QueryRouteDecision.complex(
                "planned", StructuredQuery.of("q", List.of()), List.of("a", "b"),
                Arrays.asList(null, document, document));
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(router.route("q", List.of())).thenReturn(decision);
        when(executor.execute(eq(project), same(decision), eq("q"),
                eq(List.of(document)), anyList(), eq(3), any())).thenReturn(List.of());

        retrieval.retrieve(new ProjectKnowledgeRetrieval.Request(
                project, user, "q", null, 3,
                ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE));

        verify(executor).execute(project, decision, "q",
                List.of(document), List.of(), 3, null);
    }
}
