package com.shitulelv.aicollab.agent.infrastructure.decide;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Decide 网关客户端契约测试（真实本地 {@link HttpServer} stub）：
 * 请求字段/鉴权/幂等头、四决策解析、契约违规、HTTP/超时分类与"绝不重试"。
 */
class DecideGatewayClientTest {

    private static final String TOOL = "search_project_knowledge";

    private final ObjectMapper json = new ObjectMapper();
    private StubDecideWorker worker;

    @BeforeEach
    void startStub() throws IOException {
        worker = new StubDecideWorker();
    }

    @AfterEach
    void stopStub() {
        worker.close();
    }

    @Test
    void parsesSuccessfulResponseAndSendsContractHeaders() {
        worker.responder = request -> new StubResponse(200, """
                {"schemaVersion":1,"decision":"CALL_TOOL",
                 "toolCall":{"name":"search_project_knowledge","arguments":{"query":"风险"}},
                 "usage":{"inputTokens":8123,"outputTokens":96,"source":"PROVIDER"},
                 "promptVersion":"2026-11-11.1","modelAlias":"agent-main","latencyMs":2140}
                """);

        DecideGatewayResponse response = client("test-token").decide(request(1000));

        assertThat(response.schemaVersion()).isEqualTo(1);
        assertThat(response.decision()).isEqualTo(DecideDecision.CALL_TOOL);
        assertThat(response.toolCall().name()).isEqualTo(TOOL);
        assertThat(response.toolCall().arguments().path("query").asText()).isEqualTo("风险");
        assertThat(response.usage().inputTokens()).isEqualTo(8123);
        assertThat(response.usage().outputTokens()).isEqualTo(96);
        assertThat(response.usage().source()).isEqualTo("PROVIDER");
        assertThat(response.promptVersion()).isEqualTo("2026-11-11.1");
        assertThat(response.modelAlias()).isEqualTo("agent-main");
        assertThat(response.latencyMs()).isEqualTo(2140L);

        assertThat(worker.requests()).hasSize(1);
        RecordedRequest recorded = worker.requests().getFirst();
        assertThat(recorded.idempotencyKey()).matches("[0-9a-f]{32}");
        assertThat(recorded.callId()).isEqualTo(recorded.idempotencyKey().substring(0, 16));
        assertThat(recorded.authorization()).isEqualTo("Bearer test-token");
        assertThat(recorded.body())
                .contains("\"schemaVersion\":1")
                .contains("\"runId\":\"run-1\"")
                .contains("\"attempt\":2")
                .contains("\"turnNo\":5")
                .contains("\"snapshotHash\":\"sha256:" + "a".repeat(32) + "\"")
                .contains("\"scenario\":{\"code\":\"PROJECT_RESEARCH\",\"category\":\"SCENARIO\"}")
                .contains("\"goal\":\"检查项目风险\"")
                .contains("\"locale\":\"zh-CN\"")
                .contains("\"facts\":[\"fact-1\"]")
                .contains("\"messages\":[]")
                .contains("\"observations\":[{\"tool\":\"" + TOOL + "\",\"summary\":\"摘要\"}]")
                .contains("\"tools\":[{\"name\":\"" + TOOL + "\"")
                .contains("\"risk\":\"READ_ONLY\"")
                .contains("\"allowWrite\":false")
                .contains("\"allowExternal\":false")
                .contains("\"maxInputTokens\":24000")
                .contains("\"maxOutputTokens\":1000")
                .contains("\"deadlineMs\":1000");
    }

    @Test
    void parsesAllFourDecisions() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"CALL_TOOL\","
                        + "\"toolCall\":{\"name\":\"" + TOOL + "\",\"arguments\":{}}}");
        assertThat(client("").decide(request(1000)).decision()).isEqualTo(DecideDecision.CALL_TOOL);

        for (DecideDecision decision : List.of(DecideDecision.FINAL, DecideDecision.ASK_USER,
                DecideDecision.NO_OP)) {
            worker.responder = request -> new StubResponse(200,
                    "{\"schemaVersion\":1,\"decision\":\"" + decision.name() + "\"}");
            DecideGatewayResponse response = client("").decide(request(1000));
            assertThat(response.decision()).isEqualTo(decision);
            assertThat(response.toolCall()).isNull();
        }
        assertThat(worker.requests()).hasSize(4);
    }

    @Test
    void rejectsInvalidJsonWithoutRetry() {
        worker.responder = request -> new StubResponse(200, "not-json");

        assertThatThrownBy(() -> client("").decide(request(1000)))
                .isInstanceOfSatisfying(DecideGatewayException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DecideGatewayErrorCode.SCHEMA));
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void parsesCallToolWithUnknownToolNameAndLeavesMembershipToShadow() {
        // 客户端刻意不校验工具成员资格：影子端点要观测"Python 建议了不可执行工具"，
        // 该判定由 AgentDecideShadowService 记 decide.shadow.invalid_tool_call。
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"CALL_TOOL\","
                        + "\"toolCall\":{\"name\":\"delete_everything\",\"arguments\":{}}}");

        DecideGatewayResponse response = client("").decide(request(1000));

        assertThat(response.decision()).isEqualTo(DecideDecision.CALL_TOOL);
        assertThat(response.toolCall().name()).isEqualTo("delete_everything");
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void rejectsCallToolWithoutObjectArguments() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"CALL_TOOL\","
                        + "\"toolCall\":{\"name\":\"" + TOOL + "\",\"arguments\":\"[]\"}}");

        assertThatThrownBy(() -> client("").decide(request(1000)))
                .isInstanceOfSatisfying(DecideGatewayException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DecideGatewayErrorCode.SCHEMA));
    }

    @Test
    void rejectsToolCallOnNonCallDecision() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"FINAL\","
                        + "\"toolCall\":{\"name\":\"" + TOOL + "\",\"arguments\":{}}}");

        assertThatThrownBy(() -> client("").decide(request(1000)))
                .isInstanceOfSatisfying(DecideGatewayException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DecideGatewayErrorCode.SCHEMA));
    }

    @Test
    void rejectsUnknownDecisionAndWrongSchemaVersion() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"EXECUTE\"}");
        assertThatThrownBy(() -> client("").decide(request(1000)))
                .isInstanceOfSatisfying(DecideGatewayException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DecideGatewayErrorCode.SCHEMA));

        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":2,\"decision\":\"FINAL\"}");
        assertThatThrownBy(() -> client("").decide(request(1000)))
                .isInstanceOfSatisfying(DecideGatewayException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DecideGatewayErrorCode.SCHEMA));
    }

    @Test
    void mapsUnavailableAndTimeoutStatusesWithoutRetry() {
        worker.responder = request -> new StubResponse(503, "{}");
        assertThatThrownBy(() -> client("").decide(request(1000)))
                .isInstanceOfSatisfying(DecideGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(DecideGatewayErrorCode.UNAVAILABLE));
        assertThat(worker.requests()).hasSize(1);

        worker.responder = request -> new StubResponse(504, "{}");
        assertThatThrownBy(() -> client("").decide(request(1000)))
                .isInstanceOfSatisfying(DecideGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(DecideGatewayErrorCode.TIMEOUT));
        assertThat(worker.requests()).hasSize(2);

        worker.responder = request -> new StubResponse(422, "{}");
        assertThatThrownBy(() -> client("").decide(request(1000)))
                .isInstanceOfSatisfying(DecideGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(DecideGatewayErrorCode.HTTP));
        assertThat(worker.requests()).hasSize(3);
    }

    @Test
    void classifiesReadTimeoutWithShortGrace() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"FINAL\"}", 500);
        DecideProperties properties = properties(worker.baseUrl(), 100);
        DecideGatewayClient shortTimeoutClient = new DecideGatewayClient(properties,
                () -> "", json, HttpClient.newBuilder().build(), Duration.ofMillis(150));

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> shortTimeoutClient.decide(request(100)))
                .isInstanceOfSatisfying(DecideGatewayException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DecideGatewayErrorCode.TIMEOUT));
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(3000);
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void missingUsageAndLatencyBecomeUnknownAndNull() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"NO_OP\"}");

        DecideGatewayResponse response = client("").decide(request(1000));

        assertThat(response.usage().inputTokens()).isNull();
        assertThat(response.usage().outputTokens()).isNull();
        assertThat(response.usage().source()).isEqualTo("UNKNOWN");
        assertThat(response.latencyMs()).isNull();
        assertThat(response.promptVersion()).isNull();
        assertThat(response.modelAlias()).isNull();
    }

    @Test
    void omitsAuthorizationWhenServiceTokenIsEmpty() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"FINAL\"}");

        client("").decide(request(1000));

        assertThat(worker.requests().getFirst().authorization()).isNull();
    }

    @Test
    void classifiesTransportFailures() {
        assertThat(DecideGatewayClient.classifyTransportFailure(
                new java.net.http.HttpTimeoutException("read")).reason())
                .isEqualTo(DecideGatewayErrorCode.TIMEOUT);
        assertThat(DecideGatewayClient.classifyTransportFailure(
                new java.net.http.HttpConnectTimeoutException("connect")).reason())
                .isEqualTo(DecideGatewayErrorCode.TIMEOUT);
        assertThat(DecideGatewayClient.classifyTransportFailure(
                new java.net.ConnectException("refused")).reason())
                .isEqualTo(DecideGatewayErrorCode.UNAVAILABLE);
        assertThat(DecideGatewayClient.classifyTransportFailure(
                new IOException("io")).reason()).isEqualTo(DecideGatewayErrorCode.UNAVAILABLE);
        assertThat(DecideGatewayClient.classifyTransportFailure(
                new IllegalStateException("boom")).reason())
                .isEqualTo(DecideGatewayErrorCode.UNEXPECTED);
    }

    // ---------- helpers ----------

    private DecideGatewayClient client(String serviceToken) {
        return new DecideGatewayClient(properties(worker.baseUrl(), 1000),
                () -> serviceToken, json);
    }

    private static DecideProperties properties(String baseUrl, int deadlineMs) {
        return new DecideProperties(DecideMode.SHADOW, baseUrl, deadlineMs, 8, 16, 32000);
    }

    private static DecideGatewayRequest request(int deadlineMs) {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string");
        return new DecideGatewayRequest(
                1,
                "run-1",
                2,
                5,
                "sha256:" + "a".repeat(32),
                new DecideGatewayRequest.Scenario("PROJECT_RESEARCH", "SCENARIO"),
                "检查项目风险",
                "zh-CN",
                new DecideGatewayRequest.Context(
                        List.of("fact-1"),
                        List.of(),
                        List.of(new DecideGatewayRequest.Observation(TOOL, "摘要"))),
                List.of(new DecideGatewayRequest.Tool(TOOL, "检索项目知识", schema, "READ_ONLY")),
                DecideGatewayRequest.Policy.READ_ONLY,
                new DecideGatewayRequest.Budget(24000, 1000, deadlineMs));
    }

    private static final class StubDecideWorker implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile Responder responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"FINAL\"}");

        private StubDecideWorker() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "decide-worker-stub");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext(DecideGatewayClient.DECIDE_PATH, exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                RecordedRequest recorded = new RecordedRequest(
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                        exchange.getRequestHeaders().getFirst("X-Call-Id"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
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

    private record RecordedRequest(String idempotencyKey, String callId, String authorization,
            String body) {
    }
}


