package com.shitulelv.aicollab.retrieval.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalResult;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalTrace;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import com.shitulelv.aicollab.retrieval.query.QueryRouteDecision;
import com.shitulelv.aicollab.retrieval.query.QueryRouter;
import com.shitulelv.aicollab.retrieval.query.StructuredQuery;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectKnowledgeRetrievalTest {

    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final DocumentSearchService search = mock(DocumentSearchService.class);
    private final RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
    private final QueryRouter router = mock(QueryRouter.class);
    private final ProjectKnowledgeRetrieval retrieval =
            new ProjectKnowledgeRetrieval(access, search, pipeline, router);

    private final UUID project = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private final UUID chunk = UUID.randomUUID();

    @Test
    void denseLegacyDispatchesToDocumentSearchWithoutRouterOrPipeline() {
        DocumentSearchHit hit = hit(chunk, document, "保留策略", "临时区保存二十四小时。", 0.91);
        when(search.search(project, "保留多久", List.of(document), 8)).thenReturn(List.of(hit));

        ProjectKnowledgeRetrieval.Result result = retrieval.retrieve(
                new ProjectKnowledgeRetrieval.Request(
                        project, user, "保留多久", List.of(document), 8,
                        ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY));

        assertThat(result.hits()).containsExactly(hit);
        assertThat(result.effectiveQuery()).isEqualTo("保留多久");
        assertThat(result.filters()).isEmpty();
        assertThat(result.retrievalCore()).isEqualTo(ProjectKnowledgeRetrieval.RETRIEVAL_CORE_LEGACY_DENSE);
        verify(router, never()).route(anyString());
        verify(pipeline, never()).execute(any(), anyString(), anyList(), anyList(), anyInt());
    }

    @Test
    void ragV2RoutesQueryAndPassesFiltersAndSemanticQueryToPipeline() {
        MetadataFilter filter = MetadataFilter.eq("mimeType", "application/pdf");
        when(router.route("原始问题")).thenReturn(QueryRouteDecision.structured(
                "structured", StructuredQuery.of("清洗后的问题", List.of(filter))));
        RetrievalCandidate candidate = new RetrievalCandidate(
                chunk, document, "规范.pdf", "保留策略", "临时区保存二十四小时。", "hash",
                Map.of("pageNumber", 3), 1, 0.93, null, null, 0.5, 0.7, "semantic-heuristic");
        when(pipeline.execute(project, "清洗后的问题", List.of(document), List.of(filter), 5))
                .thenReturn(new RetrievalResult(List.of(candidate), emptyTrace()));

        ProjectKnowledgeRetrieval.Result result = retrieval.retrieve(
                new ProjectKnowledgeRetrieval.Request(
                        project, user, "原始问题", List.of(document), 5,
                        ProjectKnowledgeRetrieval.Mode.RAG_V2));

        assertThat(result.hits()).containsExactly(candidate.toDocumentSearchHit());
        assertThat(result.effectiveQuery()).isEqualTo("清洗后的问题");
        assertThat(result.filters()).containsExactly(filter);
        assertThat(result.retrievalCore()).isEqualTo(ProjectKnowledgeRetrieval.RETRIEVAL_CORE_RAG_V2_PIPELINE);
        verify(search, never()).search(any(), anyString(), anyList(), anyInt());
    }

    @Test
    void ragV2KeepsRawQueryWhenRouterHasNoSemanticRewrite() {
        when(router.route("原始问题")).thenReturn(QueryRouteDecision.hybrid(
                "hybrid", StructuredQuery.of("   ", List.of())));
        when(pipeline.execute(project, "原始问题", List.of(), List.of(), 8))
                .thenReturn(new RetrievalResult(List.of(), emptyTrace()));

        ProjectKnowledgeRetrieval.Result result = retrieval.retrieve(
                new ProjectKnowledgeRetrieval.Request(
                        project, user, "原始问题", null, 8,
                        ProjectKnowledgeRetrieval.Mode.RAG_V2));

        assertThat(result.effectiveQuery()).isEqualTo("原始问题");
        assertThat(result.filters()).isEmpty();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void requireMemberRunsBeforeAnyDenseSearchWork() {
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(search.search(project, "q", List.of(), 3)).thenReturn(List.of());

        retrieval.retrieve(new ProjectKnowledgeRetrieval.Request(
                project, user, "q", List.of(), 3, ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY));

        InOrder order = inOrder(access, search);
        order.verify(access).requireMember(project, user);
        order.verify(search).search(project, "q", List.of(), 3);
    }

    @Test
    void requireMemberRunsBeforeRoutingAndPipeline() {
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(router.route("q")).thenReturn(QueryRouteDecision.hybrid("h", StructuredQuery.of("q", List.of())));
        when(pipeline.execute(project, "q", List.of(), List.of(), 3))
                .thenReturn(new RetrievalResult(List.of(), emptyTrace()));

        retrieval.retrieve(new ProjectKnowledgeRetrieval.Request(
                project, user, "q", List.of(), 3, ProjectKnowledgeRetrieval.Mode.RAG_V2));

        InOrder order = inOrder(access, router, pipeline);
        order.verify(access).requireMember(project, user);
        order.verify(router).route("q");
        order.verify(pipeline).execute(project, "q", List.of(), List.of(), 3);
    }

    @Test
    void permissionFailureStopsBeforeAnyRetrievalWork() {
        doThrow(new BusinessException(ErrorCode.PROJECT_NOT_FOUND))
                .when(access).requireMember(project, user);

        assertThatThrownBy(() -> retrieval.retrieve(new ProjectKnowledgeRetrieval.Request(
                project, user, "q", List.of(), 3, ProjectKnowledgeRetrieval.Mode.RAG_V2)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);

        verifyNoInteractions(search, pipeline, router);
    }

    @Test
    void validatesTopKQueryIdentifiersAndModeBeforeAnyWork() {
        assertValidation(new ProjectKnowledgeRetrieval.Request(
                project, user, "q", List.of(), 0, ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY));
        assertValidation(new ProjectKnowledgeRetrieval.Request(
                project, user, "q", List.of(), 21, ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY));
        assertValidation(new ProjectKnowledgeRetrieval.Request(
                project, user, "  ", List.of(), 5, ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY));
        assertValidation(new ProjectKnowledgeRetrieval.Request(
                null, user, "q", List.of(), 5, ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY));
        assertValidation(new ProjectKnowledgeRetrieval.Request(
                project, null, "q", List.of(), 5, ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY));
        assertValidation(new ProjectKnowledgeRetrieval.Request(
                project, user, "q", List.of(), 5, null));

        verifyNoInteractions(access, search, pipeline, router);
    }

    @Test
    void nullAndDuplicateDocumentIdsAreNormalizedBeforeDispatch() {
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(router.route("q")).thenReturn(QueryRouteDecision.hybrid("h", StructuredQuery.of("q", List.of())));
        when(pipeline.execute(project, "q", List.of(document), List.of(), 4))
                .thenReturn(new RetrievalResult(List.of(), emptyTrace()));

        retrieval.retrieve(new ProjectKnowledgeRetrieval.Request(
                project, user, "q", java.util.Arrays.asList(document, null, document), 4,
                ProjectKnowledgeRetrieval.Mode.RAG_V2));

        verify(pipeline).execute(project, "q", List.of(document), List.of(), 4);
    }

    @Test
    void scopeResolutionDelegatesToDocumentSearchAfterMembershipCheck() {
        DocumentSearchService.UserDocumentScope scope = new DocumentSearchService.UserDocumentScope(
                DocumentSearchService.ScopeStatus.ENFORCED, List.of("规范"), List.of(),
                List.of(document), List.of(), List.of(document), List.of());
        when(access.requireMember(project, user)).thenReturn(ProjectRole.MEMBER);
        when(search.resolveUserDocumentScope(project, "只根据《规范》")).thenReturn(scope);

        DocumentSearchService.UserDocumentScope resolved =
                retrieval.resolveUserDocumentScope(project, user, "只根据《规范》");

        assertThat(resolved).isSameAs(scope);
        InOrder order = inOrder(access, search);
        order.verify(access).requireMember(project, user);
        order.verify(search).resolveUserDocumentScope(project, "只根据《规范》");
    }

    @Test
    void scopeResolutionFailsClosedForNonMember() {
        doThrow(new BusinessException(ErrorCode.PROJECT_NOT_FOUND))
                .when(access).requireMember(project, user);

        assertThatThrownBy(() -> retrieval.resolveUserDocumentScope(project, user, "goal"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);
        verify(search, never()).resolveUserDocumentScope(any(), any());
    }

    private void assertValidation(ProjectKnowledgeRetrieval.Request request) {
        assertThatThrownBy(() -> retrieval.retrieve(request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    private static DocumentSearchHit hit(UUID chunkId, UUID documentId, String heading,
                                         String content, double similarity) {
        return new DocumentSearchHit(chunkId, documentId, "规范.pdf", heading, content, "hash",
                similarity, Map.of("pageNumber", 3));
    }

    private static RetrievalTrace emptyTrace() {
        return new RetrievalTrace(0, 0, 0, 0, 0, 0, 0, 0, false, "test",
                false, false, Map.of(), List.of());
    }
}
