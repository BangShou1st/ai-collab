package com.shitulelv.aicollab.retrieval.queryplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.retrieval.rerank.RerankMode;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QueryPlan 客户端契约测试（W4 冻结契约 §4）：本波不接线，仅用本地
 * {@link HttpServer} stub 验证请求字段、鉴权、正常解析、缺字段/未知 filter 键、
 * HTTP 500 与读超时分类。
 */
class QueryPlanGatewayClientTest {

    private final ObjectMapper json = new ObjectMapper();
    private StubPlanWorker worker;

    @BeforeEach
    void startStub() throws IOException {
        worker = new StubPlanWorker();
    }

    @AfterEach
    void stopStub() {
        worker.close();
    }

    @Test
    void parsesSuccessfulResponseAndSendsContractHeaders() {
        worker.responder = request -> new StubResponse(200, """
                {"schemaVersion":1,"rewrittenQuery":"混合架构 向量检索",
                 "subQueries":["pgvector 索引","混合检索融合"],
                 "filters":{"documentIds":["doc-1"],"docTypes":["SPEC"],
                            "dateRange":{"from":"2026-01-01","to":"2026-06-30"}},
                 "confidence":0.78}
                """);

        QueryPlanResponse response = client("test-token").plan(
                new QueryPlanRequest("混合架构", "zh-CN", "上一轮在问向量检索", List.of("doc-1", "doc-2")));

        assertThat(response.schemaVersion()).isEqualTo(1);
        assertThat(response.rewrittenQuery()).isEqualTo("混合架构 向量检索");
        assertThat(response.subQueries()).containsExactly("pgvector 索引", "混合检索融合");
        assertThat(response.filters().documentIds()).containsExactly("doc-1");
        assertThat(response.filters().docTypes()).containsExactly("SPEC");
        assertThat(response.filters().dateRange().from()).isEqualTo("2026-01-01");
        assertThat(response.filters().dateRange().to()).isEqualTo("2026-06-30");
        assertThat(response.confidence()).isEqualTo(0.78);

        assertThat(worker.requests()).hasSize(1);
        RecordedRequest recorded = worker.requests().getFirst();
        assertThat(recorded.authorization()).isEqualTo("Bearer test-token");
        // 只读建议端点：本波不发送幂等键。
        assertThat(recorded.idempotencyKey()).isNull();
        assertThat(recorded.body())
                .contains("\"schemaVersion\":1")
                .contains("\"query\":\"混合架构\"")
                .contains("\"locale\":\"zh-CN\"")
                .contains("\"historySummary\":\"上一轮在问向量检索\"")
                .contains("\"documentIds\":[\"doc-1\",\"doc-2\"]")
                .contains("\"deadlineMs\":8000");
    }

    @Test
    void omitsBlankHistorySummaryAndUsesConfiguredDeadline() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"rewrittenQuery\":\"q\",\"subQueries\":[],\"confidence\":0.5}");

        client("").plan(new QueryPlanRequest("混合架构", null, "", List.of()));

        RecordedRequest recorded = worker.requests().getFirst();
        assertThat(recorded.body())
                .doesNotContain("historySummary")
                .contains("\"locale\":\"zh-CN\"")
                .contains("\"deadlineMs\":8000");
        assertThat(recorded.authorization()).isNull();
    }

    @Test
    void rejectsMissingRewrittenQuery() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"subQueries\":[],\"confidence\":0.5}");

        assertThatThrownBy(() -> client("").plan(request()))
                .isInstanceOfSatisfying(QueryPlanGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(QueryPlanGatewayException.Reason.SCHEMA));
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void rejectsUnknownFilterKeys() {
        worker.responder = request -> new StubResponse(200, """
                {"schemaVersion":1,"rewrittenQuery":"q","subQueries":[],
                 "filters":{"documentIds":["doc-1"],"sources":["web"]},"confidence":0.7}
                """);

        assertThatThrownBy(() -> client("").plan(request()))
                .isInstanceOfSatisfying(QueryPlanGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(QueryPlanGatewayException.Reason.SCHEMA));
    }

    @Test
    void rejectsInvalidDateRangeAndConfidence() {
        worker.responder = request -> new StubResponse(200, """
                {"schemaVersion":1,"rewrittenQuery":"q","subQueries":[],
                 "filters":{"dateRange":{"from":"2026-06-30","to":"2026-01-01"}},"confidence":0.7}
                """);
        assertThatThrownBy(() -> client("").plan(request()))
                .isInstanceOfSatisfying(QueryPlanGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(QueryPlanGatewayException.Reason.SCHEMA));

        worker.responder = request -> new StubResponse(200, """
                {"schemaVersion":1,"rewrittenQuery":"q","subQueries":[],"confidence":1.7}
                """);
        assertThatThrownBy(() -> client("").plan(request()))
                .isInstanceOfSatisfying(QueryPlanGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(QueryPlanGatewayException.Reason.SCHEMA));
    }

    @Test
    void mapsHttp500ToUnavailableWithoutRetry() {
        worker.responder = request -> new StubResponse(500, "{}");

        assertThatThrownBy(() -> client("").plan(request()))
                .isInstanceOfSatisfying(QueryPlanGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(QueryPlanGatewayException.Reason.UNAVAILABLE));
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void classifiesReadTimeoutWithShortGrace() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"rewrittenQuery\":\"q\",\"subQueries\":[],\"confidence\":0.5}",
                500);
        QueryPlanProperties properties = new QueryPlanProperties(true, worker.baseUrl(), 100);
        QueryPlanGatewayClient shortTimeoutClient = new QueryPlanGatewayClient(properties,
                () -> "", json, HttpClient.newBuilder().build(), Duration.ofMillis(150));

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> shortTimeoutClient.plan(request()))
                .isInstanceOfSatisfying(QueryPlanGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(QueryPlanGatewayException.Reason.TIMEOUT));
        assertThat((System.nanoTime() - startNanos) / 1_000_000L).isLessThan(3000);
        assertThat(worker.requests()).hasSize(1);
    }

    // ---------- helpers ----------

    private QueryPlanGatewayClient client(String serviceToken) {
        return new QueryPlanGatewayClient(
                new QueryPlanProperties(true, worker.baseUrl(), 8000),
                () -> serviceToken, json);
    }

    private static QueryPlanRequest request() {
        return new QueryPlanRequest("混合架构", "zh-CN", "", List.of("doc-1"));
    }

    private static final class StubPlanWorker implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile Responder responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"rewrittenQuery\":\"q\",\"subQueries\":[],\"confidence\":0.5}");

        private StubPlanWorker() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "query-plan-worker-stub");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext(QueryPlanGatewayClient.PLAN_PATH, exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                RecordedRequest recorded = new RecordedRequest(
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                        body);
                requests.add(recorded);
                StubResponse response = responder.respond(recorded);
                if (response.delayMs() > 0) {
                    try {
                        Thread.sleep(response.delayMs());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type",
                            "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(response.status(), payload.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(payload);
                    }
                } catch (IOException clientGone) {
                    // 读超时测试中客户端已断开：stub 侧写入失败是预期噪声。
                }
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Responder {
        StubResponse respond(RecordedRequest request);
    }

    private record StubResponse(int status, String body, long delayMs) {
        private StubResponse(int status, String body) {
            this(status, body, 0);
        }
    }

    private record RecordedRequest(String authorization, String idempotencyKey, String body) {
    }
}


