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
import com.shitulelv.aicollab.retrieval.query.RetrievalStrategy;
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
    private final LegacyProjectKnowledgeRetrieval retrieval =
            new LegacyProjectKnowledgeRetrieval(access, search, pipeline, router, executor);

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
        when(router.route("原始问题", List.of(document, otherDocument), user, project)).thenReturn(decision);
        when(executor.execute(project, decision, "计划改写后的查询",
                List.of(document), List.of(filter), 8, probe)).thenReturn(List.of(hit));

        LegacyProjectKnowledgeRetrieval.Result result = retrieval.retrieve(
                new LegacyProjectKnowledgeRetrieval.Request(
                        project, user, "原始问题", List.of(document, otherDocument), 8,
                        LegacyProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE),
                probe);

        assertThat(result.hits()).containsExactly(hit);
        assertThat(result.effectiveQuery()).isEqualTo("计划改写后的查询");
        assertThat(result.filters()).containsExactly(filter);
        assertThat(result.retrievalCore())
                .isEqualTo(LegacyProjectKnowledgeRetrieval.RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE);
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
        when(router.route("q", List.of(document), user, project)).thenReturn(decision);
        when(executor.execute(eq(project), same(decision), eq("q"),
                eq(List.of(document)), anyList(), eq(3), any()))
                .thenReturn(List.of());

        retrieval.retrieve(new LegacyProjectKnowledgeRetrieval.Request(
                project, user, "q", List.of(document), 3,
                LegacyProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE));

        verify(executor).execute(project, decision, "q",
                List.of(document), List.of(), 3, null);
    }

    @Test
    void missingExecutorFailsClosedOnlyWhenStrategyAwareIsRequested() {
        LegacyProjectKnowledgeRetrieval legacyWiring =
                new LegacyProjectKnowledgeRetrieval(access, search, pipeline, router);
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);

        assertThatThrownBy(() -> legacyWiring.retrieve(
                new LegacyProjectKnowledgeRetrieval.Request(
                        project, user, "q", null, 3,
                        LegacyProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("StrategyAwareRetrievalExecutor");
    }

    @Test
    void nullAndDuplicatePlannedDocumentIdsAreNormalized() {
        QueryRouteDecision decision = QueryRouteDecision.complex(
                "planned", StructuredQuery.of("q", List.of()), List.of("a", "b"),
                Arrays.asList(null, document, document));
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(router.route("q", List.of(), user, project)).thenReturn(decision);
        when(executor.execute(eq(project), same(decision), eq("q"),
                eq(List.of(document)), anyList(), eq(3), any())).thenReturn(List.of());

        retrieval.retrieve(new LegacyProjectKnowledgeRetrieval.Request(
                project, user, "q", null, 3,
                LegacyProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE));

        verify(executor).execute(project, decision, "q",
                List.of(document), List.of(), 3, null);
    }

    @Test
    void ragV2ModeDelegatesToStrategyAwareExecutorWhenPresentAndPopulatesAppliedStrategy() {
        QueryRouteDecision decision = QueryRouteDecision.complex(
                "planned", StructuredQuery.of("rewritten", List.of()),
                List.of("s1", "s2"), List.of(document));
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(router.route("raw", List.of(document, otherDocument), user, project)).thenReturn(decision);
        when(executor.execute(eq(project), same(decision), eq("rewritten"),
                eq(List.of(document)), anyList(), eq(6), any()))
                .thenReturn(List.of());

        LegacyProjectKnowledgeRetrieval.Result result = retrieval.retrieve(new LegacyProjectKnowledgeRetrieval.Request(
                project, user, "raw", List.of(document, otherDocument), 6,
                LegacyProjectKnowledgeRetrieval.Mode.RAG_V2));

        assertThat(result.retrievalCore()).isEqualTo(LegacyProjectKnowledgeRetrieval.RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE);
        assertThat(result.appliedStrategy()).isEqualTo(RetrievalStrategy.MULTI_QUERY_HYBRID);
        verify(router).route("raw", List.of(document, otherDocument), user, project);
    }

    @Test
    void intentRequestsRouteAllFourBusinessIntentsThroughStrategyAwareAndHonorLegacyKillSwitch() {
        QueryRouteDecision decision = QueryRouteDecision.hybrid("hybrid", StructuredQuery.of("q", List.of()));
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(router.route("q", List.of(document), user, project)).thenReturn(decision);
        when(executor.execute(eq(project), same(decision), eq("q"),
                eq(List.of(document)), anyList(), eq(5), any())).thenReturn(List.of());

        for (LegacyProjectKnowledgeRetrieval.RetrievalIntent intent : LegacyProjectKnowledgeRetrieval.RetrievalIntent.values()) {
            LegacyProjectKnowledgeRetrieval.Result result = retrieval.retrieve(
                    new LegacyProjectKnowledgeRetrieval.IntentRequest(
                            project, user, "q", List.of(document), 5, intent));
            assertThat(result.retrievalCore()).isEqualTo(LegacyProjectKnowledgeRetrieval.RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE);
            assertThat(result.appliedStrategy()).isEqualTo(decision.strategy());
        }

        retrieval.setRetrievalQaProperties(new RetrievalQaProperties("DENSE_LEGACY", false));
        when(search.search(project, "q", List.of(document), 5)).thenReturn(List.of());
        LegacyProjectKnowledgeRetrieval.Result qaLegacy = retrieval.retrieve(new LegacyProjectKnowledgeRetrieval.IntentRequest(
                project, user, "q", List.of(document), 5, LegacyProjectKnowledgeRetrieval.RetrievalIntent.KNOWLEDGE_QA));
        assertThat(qaLegacy.retrievalCore()).isEqualTo(LegacyProjectKnowledgeRetrieval.RETRIEVAL_CORE_LEGACY_DENSE);
        LegacyProjectKnowledgeRetrieval.Result agentStillAware = retrieval.retrieve(new LegacyProjectKnowledgeRetrieval.IntentRequest(
                project, user, "q", List.of(document), 5, LegacyProjectKnowledgeRetrieval.RetrievalIntent.AGENT_RESEARCH));
        assertThat(agentStillAware.retrievalCore()).isEqualTo(LegacyProjectKnowledgeRetrieval.RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE);

        retrieval.setForceLegacyDense(true);
        LegacyProjectKnowledgeRetrieval.Result forcedLegacy = retrieval.retrieve(new LegacyProjectKnowledgeRetrieval.IntentRequest(
                project, user, "q", List.of(document), 5, LegacyProjectKnowledgeRetrieval.RetrievalIntent.PLANNING_SOURCE));
        assertThat(forcedLegacy.retrievalCore()).isEqualTo(LegacyProjectKnowledgeRetrieval.RETRIEVAL_CORE_LEGACY_DENSE);
    }
}
