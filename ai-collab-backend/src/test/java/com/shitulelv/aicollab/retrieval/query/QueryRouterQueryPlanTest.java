package com.shitulelv.aicollab.retrieval.query;

import com.shitulelv.aicollab.retrieval.queryplan.QueryPlanGatewayClient;
import com.shitulelv.aicollab.retrieval.queryplan.QueryPlanGatewayException;
import com.shitulelv.aicollab.retrieval.queryplan.QueryPlanProperties;
import com.shitulelv.aicollab.retrieval.queryplan.QueryPlanResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueryRouterQueryPlanTest {

    private static final String COMPLEX_QUERY =
            "Redis和Memcached的区别是什么？各自适用什么场景？";
    private static final QueryPlanProperties ENABLED =
            new QueryPlanProperties(true, "http://127.0.0.1:19100", 1000);

    @Test
    void disabledSwitch_neverCallsWorkerAndKeepsLocalDecomposition() {
        QueryPlanGatewayClient client = mock(QueryPlanGatewayClient.class);
        QueryRouter router = router(client, new QueryPlanProperties(
                false, "http://127.0.0.1:19100", 1000));

        QueryRouteDecision decision = router.route(COMPLEX_QUERY);

        assertThat(decision.complexity()).isEqualTo(QueryComplexity.COMPLEX);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.MULTI_QUERY_HYBRID);
        assertThat(decision.subQueries().size()).isGreaterThanOrEqualTo(2);
        assertThat(decision.documentIds()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void nonComplexQuery_neverCallsWorker() {
        QueryPlanGatewayClient client = mock(QueryPlanGatewayClient.class);
        QueryRouter router = router(client, ENABLED);

        QueryRouteDecision decision = router.route("pgvector是什么");

        assertThat(decision.complexity()).isEqualTo(QueryComplexity.FAST);
        verifyNoInteractions(client);
    }

    @Test
    void highConfidencePlan_adoptsRewriteSubqueriesAndBoundedFilters() {
        UUID documentId = UUID.randomUUID();
        QueryPlanGatewayClient client = mock(QueryPlanGatewayClient.class);
        when(client.plan(any())).thenReturn(new QueryPlanResponse(
                1,
                "Redis 与 Memcached 的缓存架构对比",
                List.of("Redis 缓存架构", "Memcached 缓存架构"),
                new QueryPlanResponse.Filters(
                        List.of(documentId.toString()),
                        List.of("SPEC"),
                        new QueryPlanResponse.DateRange("2026-01-01", "2026-06-30")),
                0.86));
        QueryRouter router = router(client, ENABLED);

        QueryRouteDecision decision = router.route(COMPLEX_QUERY);

        assertThat(decision.complexity()).isEqualTo(QueryComplexity.COMPLEX);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.MULTI_QUERY_HYBRID);
        assertThat(decision.reason()).contains("QueryPlan adopted");
        assertThat(decision.structuredQuery().semanticQuery())
                .isEqualTo("Redis 与 Memcached 的缓存架构对比");
        assertThat(decision.subQueries())
                .containsExactly("Redis 缓存架构", "Memcached 缓存架构");
        assertThat(decision.documentIds()).containsExactly(documentId);
        assertThat(decision.structuredQuery().filters()).hasSize(2);
        assertThat(decision.structuredQuery().filters().get(0).field()).isEqualTo("createdAt");
        assertThat(decision.structuredQuery().filters().get(0).operator())
                .isEqualTo(FilterOperator.GTE);
        assertThat(decision.structuredQuery().filters().get(1).field()).isEqualTo("createdAt");
        assertThat(decision.structuredQuery().filters().get(1).operator())
                .isEqualTo(FilterOperator.LTE);
        verify(client).plan(any());
    }

    @Test
    void lowConfidencePlan_fallsBackToLocalDecomposition() {
        QueryPlanGatewayClient client = mock(QueryPlanGatewayClient.class);
        when(client.plan(any())).thenReturn(new QueryPlanResponse(
                1, "worker rewrite", List.of("worker child"), QueryPlanResponse.Filters.empty(), 0.49));
        QueryRouter router = router(client, ENABLED);

        QueryRouteDecision decision = router.route(COMPLEX_QUERY);

        assertThat(decision.reason()).doesNotContain("QueryPlan adopted");
        assertThat(decision.structuredQuery().semanticQuery()).isEqualTo(COMPLEX_QUERY);
        assertThat(decision.subQueries()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(decision.documentIds()).isEmpty();
    }

    @Test
    void timeoutPlan_fallsBackToLocalRulesWithoutPropagatingFailure() {
        QueryPlanGatewayClient client = mock(QueryPlanGatewayClient.class);
        when(client.plan(any())).thenThrow(new QueryPlanGatewayException(
                QueryPlanGatewayException.Reason.TIMEOUT, "timeout"));
        QueryRouter router = router(client, ENABLED);

        QueryRouteDecision decision = router.route(COMPLEX_QUERY);

        assertThat(decision.complexity()).isEqualTo(QueryComplexity.COMPLEX);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.MULTI_QUERY_HYBRID);
        assertThat(decision.reason()).doesNotContain("QueryPlan adopted");
        assertThat(decision.structuredQuery().semanticQuery()).isEqualTo(COMPLEX_QUERY);
    }

    @Test
    void enabledWithoutClient_safelyUsesLocalRules() {
        QueryRouter router = router(null, ENABLED);

        QueryRouteDecision decision = router.route(COMPLEX_QUERY);

        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.MULTI_QUERY_HYBRID);
        assertThat(decision.subQueries().size()).isGreaterThanOrEqualTo(2);
    }

    private static QueryRouter router(QueryPlanGatewayClient client, QueryPlanProperties properties) {
        return new QueryRouter(new MetadataFilterParser(), new MultiQueryDecomposer(), client, properties);
    }
}
