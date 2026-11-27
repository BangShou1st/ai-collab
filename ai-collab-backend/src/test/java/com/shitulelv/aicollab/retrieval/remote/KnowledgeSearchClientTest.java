package com.shitulelv.aicollab.retrieval.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P2 契约 §4.4 / P2-R2：检索客户端前置校验、状态码分支、重试次数与响应结构校验（不依赖 Docker）。 */
class KnowledgeSearchClientTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DOC = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String TOKEN = String.join("-", "token", "under", "test");

    private HttpClient http;
    private InternalTokenProvider tokens;
    private KnowledgeSearchClient client;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        tokens = mock(InternalTokenProvider.class);
        when(tokens.knowledgeSearchToken(USER, PROJECT)).thenReturn(TOKEN);
        client = new KnowledgeSearchClient(KnowledgeSearchProperties.defaults(), tokens, json, http);
    }

    @Test
    void moreThan5000VisibleDocumentsIsRejectedBeforeAnyCall() throws Exception {
        List<UUID> ids = IntStream.range(0, 5001).mapToObj(i -> UUID.randomUUID()).toList();
        assertCode(() -> client.search(request(ids)), ErrorCode.VALIDATION_ERROR);
        verify(http, never()).send(any(), any());
        verify(tokens, never()).knowledgeSearchToken(any(), any());
    }

    @Test
    void exactly5000IsAllowed() throws Exception {
        List<UUID> ids = IntStream.range(0, 5000).mapToObj(i -> UUID.randomUUID()).toList();
        KnowledgeSearchModels.Request request = request(ids);
        stub(200, responseJson(request.requestId(), 0.5));
        assertThat(client.search(request).hits()).hasSize(1);
    }

    @Test
    void emptyVisibleSetReturnsEmptyWithoutCalling() throws Exception {
        KnowledgeSearchModels.Response response = client.search(request(List.of()));
        assertThat(response.hits()).isEmpty();
        verify(http, never()).send(any(), any());
    }

    @Test
    void requestCarriesVisibleIdsAndBearerToken() throws Exception {
        KnowledgeSearchModels.Request request = request(List.of(DOC));
        stub(200, responseJson(request.requestId(), 0.42));

        KnowledgeSearchModels.Response response = client.search(request);

        assertThat(response.hits().getFirst().scores().finalScore()).isEqualTo(0.42);
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any());
        assertThat(sent.getValue().uri().toString()).isEqualTo("http://ai-worker:9100/v1/knowledge/search");
        assertThat(sent.getValue().headers().firstValue("Authorization")).contains(String.join(" ", "Bearer", TOKEN));
        String body = json.writeValueAsString(request);
        assertThat(body).contains("\"visibleDocumentIds\":[\"" + DOC + "\"]").contains("\"candidatePool\":20");
    }

    @Test
    void invalidQueryShapesAreRejected() throws Exception {
        assertCode(() -> client.search(withQueries(List.of())), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(withQueries(List.of(KnowledgeSearchModels.Query.sub("x")))),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(withQueries(List.of(KnowledgeSearchModels.Query.primary("a"),
                KnowledgeSearchModels.Query.primary("b")))), ErrorCode.VALIDATION_ERROR);
        List<KnowledgeSearchModels.Query> six = new ArrayList<>();
        six.add(KnowledgeSearchModels.Query.primary("p"));
        for (int i = 0; i < 5; i++) six.add(KnowledgeSearchModels.Query.sub("s" + i));
        assertCode(() -> client.search(withQueries(six)), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(withQueries(List.of(KnowledgeSearchModels.Query.primary("x".repeat(2001))))),
                ErrorCode.VALIDATION_ERROR);
        verify(http, never()).send(any(), any());
    }

    @Test
    void candidatePoolFollowsP2R2() {
        assertThat(KnowledgeSearchClient.candidatePool(1)).isEqualTo(20);
        assertThat(KnowledgeSearchClient.candidatePool(8)).isEqualTo(20);
        assertThat(KnowledgeSearchClient.candidatePool(20)).isEqualTo(40);
    }

    @Test
    void status409MapsToIndexUnavailableWithoutRetry() throws Exception {
        stub(409, "{}");
        assertCode(() -> client.search(request(List.of(DOC))), ErrorCode.KNOWLEDGE_INDEX_UNAVAILABLE);
        verify(http, times(1)).send(any(), any());
    }

    @Test
    void status503RetriesOnceThenFails() throws Exception {
        stub(503, "{\"code\":\"RERANK_UNAVAILABLE\"}");
        assertCode(() -> client.search(request(List.of(DOC))), ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);
        verify(http, times(2)).send(any(), any());
        verify(tokens, times(2)).knowledgeSearchToken(USER, PROJECT);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void other5xxRetriesOnceAndCanRecover() throws Exception {
        KnowledgeSearchModels.Request request = request(List.of(DOC));
        HttpResponse failure = response(500, "{}");
        HttpResponse success = response(200, responseJson(request.requestId(), 0.3));
        when(http.send(any(), any())).thenReturn(failure).thenReturn(success);
        assertThat(client.search(request).hits()).hasSize(1);
        verify(http, times(2)).send(any(), any());
    }

    @Test
    void status400_401_403_504AreNotRetried() throws Exception {
        for (int status : new int[] {400, 401, 403, 504}) {
            setUp();
            stub(status, "{}");
            ErrorCode expected = status == 400 ? ErrorCode.VALIDATION_ERROR
                    : status == 504 ? ErrorCode.KNOWLEDGE_SEARCH_TIMEOUT : ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE;
            assertCode(() -> client.search(request(List.of(DOC))), expected);
            verify(http, times(1)).send(any(), any());
        }
    }

    @Test
    void transportFailureRetriesOnceAndTimeoutDoesNot() throws Exception {
        when(http.send(any(), any())).thenThrow(new ConnectException("refused"));
        assertCode(() -> client.search(request(List.of(DOC))), ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);
        verify(http, times(2)).send(any(), any());

        setUp();
        when(http.send(any(), any())).thenThrow(new HttpTimeoutException("slow"));
        assertCode(() -> client.search(request(List.of(DOC))), ErrorCode.KNOWLEDGE_SEARCH_TIMEOUT);
        verify(http, times(1)).send(any(), any());
    }

    @Test
    void responseContractViolationsFailClosed() throws Exception {
        KnowledgeSearchModels.Request request = request(List.of(DOC));
        stub(200, responseJson(request.requestId(), 1.5));
        assertCode(() -> client.search(request), ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);

        setUp();
        stub(200, responseJson(UUID.randomUUID(), 0.5));
        assertCode(() -> client.search(request), ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);

        setUp();
        stub(200, "{\"requestId\":\"" + request.requestId() + "\"}");
        assertCode(() -> client.search(request), ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);

        setUp();
        stub(200, "not json");
        assertCode(() -> client.search(request), ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);
    }

    @Test
    void missingTokenSupportFailsClosedWithoutCalling() throws Exception {
        InternalTokenProvider legacy = (u, p) -> "plan-only";
        KnowledgeSearchClient noToken = new KnowledgeSearchClient(KnowledgeSearchProperties.defaults(), legacy,
                json, http);
        assertCode(() -> noToken.search(request(List.of(DOC))), ErrorCode.KNOWLEDGE_SEARCH_UNAVAILABLE);
        verify(http, never()).send(any(), any());
    }

    @Test
    void originalQueryHeadingFilterExpandStrategyAndDeadlineUpperBoundAreValidated() throws Exception {
        KnowledgeSearchModels.Request base = request(List.of(DOC));
        // originalQuery null / blank / > 2000
        assertCode(() -> client.search(copyWith(base, base.strategy(), null,
                base.headingFilter(), base.expand(), base.deadlineMs())), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(copyWith(base, base.strategy(), "   ",
                base.headingFilter(), base.expand(), base.deadlineMs())), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(copyWith(base, base.strategy(), "x".repeat(2001),
                base.headingFilter(), base.expand(), base.deadlineMs())), ErrorCode.VALIDATION_ERROR);

        // headingFilter null / blank / > 300
        assertCode(() -> client.search(copyWith(base, base.strategy(), base.originalQuery(),
                null, base.expand(), base.deadlineMs())), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(copyWith(base, base.strategy(), base.originalQuery(),
                new KnowledgeSearchModels.HeadingFilter("", null), base.expand(), base.deadlineMs())),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(copyWith(base, base.strategy(), base.originalQuery(),
                new KnowledgeSearchModels.HeadingFilter(null, " "), base.expand(), base.deadlineMs())),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(copyWith(base, base.strategy(), base.originalQuery(),
                new KnowledgeSearchModels.HeadingFilter("h".repeat(301), null), base.expand(), base.deadlineMs())),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(copyWith(base, base.strategy(), base.originalQuery(),
                new KnowledgeSearchModels.HeadingFilter(null, "h".repeat(301)), base.expand(), base.deadlineMs())),
                ErrorCode.VALIDATION_ERROR);

        // expand null / unknown strategy / deadlineMs <= 0 or > 600_000
        assertCode(() -> client.search(copyWith(base, base.strategy(), base.originalQuery(),
                base.headingFilter(), null, base.deadlineMs())), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(copyWith(base, "UNKNOWN_STRATEGY", base.originalQuery(),
                base.headingFilter(), base.expand(), base.deadlineMs())), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(copyWith(base, base.strategy(), base.originalQuery(),
                base.headingFilter(), base.expand(), 0)), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> client.search(copyWith(base, base.strategy(), base.originalQuery(),
                base.headingFilter(), base.expand(), 600_001)), ErrorCode.VALIDATION_ERROR);
        verify(http, never()).send(any(), any());

        // valid boundary values (heading 300 chars + deadlineMs 600_000) succeed
        KnowledgeSearchModels.Request boundary = copyWith(base, base.strategy(), base.originalQuery(),
                new KnowledgeSearchModels.HeadingFilter("h".repeat(300), "c".repeat(300)),
                base.expand(), 600_000);
        stub(200, responseJson(boundary.requestId(), 0.6));
        assertThat(client.search(boundary).hits()).hasSize(1);
    }

    // ---------- helpers ----------

    private static KnowledgeSearchModels.Request copyWith(
            KnowledgeSearchModels.Request base,
            String strategy,
            String originalQuery,
            KnowledgeSearchModels.HeadingFilter headingFilter,
            KnowledgeSearchModels.Expand expand,
            int deadlineMs) {
        return new KnowledgeSearchModels.Request(base.requestId(), base.projectId(), base.userId(), base.intent(),
                strategy, base.queries(), originalQuery, base.visibleDocumentIds(), base.topK(),
                base.candidatePool(), headingFilter, base.rerank(), expand, deadlineMs);
    }

    private static KnowledgeSearchModels.Request request(List<UUID> visible) {
        return new KnowledgeSearchModels.Request(UUID.randomUUID(), PROJECT, USER,
                KnowledgeSearchModels.Intent.KNOWLEDGE_QA, "DENSE_WITH_BOUNDED_FALLBACK",
                List.of(KnowledgeSearchModels.Query.primary("上线门禁")), "上线门禁是什么", visible, 8,
                KnowledgeSearchClient.candidatePool(8), KnowledgeSearchModels.HeadingFilter.none(),
                KnowledgeSearchModels.Rerank.HEURISTIC, new KnowledgeSearchModels.Expand(true, true), 8000);
    }

    private static KnowledgeSearchModels.Request withQueries(List<KnowledgeSearchModels.Query> queries) {
        KnowledgeSearchModels.Request base = request(List.of(DOC));
        return new KnowledgeSearchModels.Request(base.requestId(), base.projectId(), base.userId(), base.intent(),
                base.strategy(), queries, base.originalQuery(), base.visibleDocumentIds(), base.topK(),
                base.candidatePool(), base.headingFilter(), base.rerank(), base.expand(), base.deadlineMs());
    }

    private static String responseJson(UUID requestId, double finalScore) {
        return """
                {"requestId":"%s","generationId":"%s","strategy":"DENSE_WITH_BOUNDED_FALLBACK",
                 "hits":[{"rank":1,"documentId":"%s","chunkNo":3,"contentHash":"%s","chunkType":"STANDARD",
                   "parentChunkNo":null,"heading":"h","content":"c","bodySnapshotId":"%s",
                   "sourceRef":{"charFrom":0},"context":null,
                   "scores":{"dense":0.5,"lexical":null,"rrf":0.016,"rerank":0.9,"final":%s}}],
                 "diagnostics":{"rerank":"HEURISTIC"}}
                """.formatted(requestId, UUID.randomUUID(), DOC, "a".repeat(64), UUID.randomUUID(), finalScore);
    }

    @SuppressWarnings("unchecked")
    private void stub(int status, String body) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = response(status, body);
        when(http.send(any(), any())).thenReturn((HttpResponse) response);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }
}
