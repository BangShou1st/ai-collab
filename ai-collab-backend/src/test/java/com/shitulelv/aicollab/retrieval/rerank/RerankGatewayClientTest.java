package com.shitulelv.aicollab.retrieval.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProvider;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rerank 网关客户端测试：真实本地 {@link HttpServer} stub 验证重试次数、幂等键稳定性、
 * 请求头、响应解析与错误分类（参照 {@code PromptWorkerStub} 的写法）。
 */
class RerankGatewayClientTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUCCESS_BODY = """
            {"schemaVersion":1,"scores":[{"id":"c1","score":0.83},{"id":"c2","score":0.2}],
             "modelAlias":"rerank-default",
             "usage":{"inputTokens":0,"outputTokens":0,"source":"PROVIDER"}}
            """;

    private StubWorker worker;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void startStub() throws IOException {
        worker = new StubWorker();
    }

    @AfterEach
    void stopStub() {
        MDC.clear();
        worker.close();
    }

    @Test
    void parsesSuccessfulResponseAndSendsContractHeaders() {
        worker.responder = request -> new StubResponse(200, SUCCESS_BODY, 0);
        RerankGatewayClient client = client("test-token");

        RerankGatewayResponse response = client.rerank(
                request("混合架构", 2, candidate("c1", "标题一", "正文一"), candidate("c2", "标题二", "正文二")));

        assertThat(response.schemaVersion()).isEqualTo(1);
        assertThat(response.scores()).hasSize(2)
                .containsEntry("c1", 0.83)
                .containsEntry("c2", 0.2);
        assertThat(response.modelAlias()).isEqualTo("rerank-default");
        assertThat(response.usage().source()).isEqualTo("PROVIDER");
        assertThat(response.usage().inputTokens()).isZero();
        assertThat(response.usage().outputTokens()).isZero();

        assertThat(worker.requests()).hasSize(1);
        RecordedRequest recorded = worker.requests().getFirst();
        assertThat(recorded.idempotencyKey()).matches("[0-9a-f]{32}");
        assertThat(recorded.callId()).isEqualTo(
                "rerank:" + PROJECT_ID + ":" + recorded.idempotencyKey().substring(0, 8) + ":1");
        assertThat(recorded.authorization()).isEqualTo("Bearer test-token");
        assertThat(recorded.body())
                .contains("\"schemaVersion\":1")
                .contains("\"query\":\"混合架构\"")
                .contains("\"topK\":2")
                .contains("\"deadlineMs\":1000");
    }

    @Test
    void retriesTwiceOn503AndReusesIdempotencyKey() {
        AtomicInteger attempts = new AtomicInteger();
        worker.responder = request -> attempts.incrementAndGet() <= 2
                ? new StubResponse(503, "{}", 0)
                : new StubResponse(200, SUCCESS_BODY, 0);

        RerankGatewayResponse response = client("").rerank(
                request("混合架构", 2, candidate("c1", "标题一", "正文一"), candidate("c2", "标题二", "正文二")));

        assertThat(response.scores()).containsEntry("c1", 0.83);
        assertThat(worker.requests()).hasSize(3);
        List<String> keys = worker.requests().stream().map(RecordedRequest::idempotencyKey).toList();
        assertThat(keys).hasSize(3).allMatch(key -> key.equals(keys.getFirst()));
        String callIdPrefix = "rerank:" + PROJECT_ID + ":" + keys.getFirst().substring(0, 8) + ":";
        assertThat(worker.requests()).extracting(RecordedRequest::callId)
                .containsExactly(callIdPrefix + "1", callIdPrefix + "2", callIdPrefix + "3");
        assertThat(worker.requests().getFirst().authorization()).isNull();
    }

    @Test
    void fetchesAFreshTokenForEveryAttemptAndIgnoresStaticToken() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger tokenCalls = new AtomicInteger();
        worker.responder = request -> attempts.incrementAndGet() <= 2
                ? new StubResponse(503, "{}", 0)
                : new StubResponse(200, SUCCESS_BODY, 0);
        InternalTokenProvider provider = () -> "provider-token-" + tokenCalls.incrementAndGet();
        RerankProperties properties = new RerankProperties(RerankMode.PYTHON, worker.baseUrl(), 1000,
                Duration.ofSeconds(2), "static-should-not-be-used", 20, 8, 1.0, "");

        RerankGatewayClient client = new RerankGatewayClient(
                properties, new ObjectMapper(), registry, provider);
        client.rerank(request("混合架构", 2, candidate("c1", "标题一", "正文一"),
                candidate("c2", "标题二", "正文二")));

        // Ed25519 提供者每次取令牌都签发新 JWS；重试的 3 次尝试必须各自取一次。
        assertThat(tokenCalls).hasValue(3);
        assertThat(worker.requests()).extracting(RecordedRequest::authorization)
                .containsExactly("Bearer provider-token-1", "Bearer provider-token-2",
                        "Bearer provider-token-3");
    }

    @Test
    void doesNotRetryOn400() {
        worker.responder = request -> new StubResponse(400, "{\"code\":\"INVALID_REQUEST\"}", 0);

        assertThatThrownBy(() -> client("").rerank(
                request("混合架构", 2, candidate("c1", "标题一", "正文一"), candidate("c2", "标题二", "正文二"))))
                .isInstanceOfSatisfying(RerankGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(RerankGatewayErrorCode.INVALID_REQUEST);
                    assertThat(failure.isRetryable()).isFalse();
                });
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void retriesConnectionFailuresWithBackoff() throws IOException {
        int unusedPort = unusedPort();
        RerankProperties properties = new RerankProperties(RerankMode.PYTHON,
                "http://127.0.0.1:" + unusedPort, 1000, Duration.ofSeconds(2), "", 20, 8, 1.0, "");
        RerankGatewayClient client = new RerankGatewayClient(properties, new ObjectMapper(), registry);

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> client.rerank(
                request("混合架构", 2, candidate("c1", "标题一", "正文一"), candidate("c2", "标题二", "正文二"))))
                .isInstanceOfSatisfying(RerankGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(RerankGatewayErrorCode.MODEL_UNAVAILABLE);
                    assertThat(failure.isRetryable()).isTrue();
                });
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // 100ms + 200ms 两次退避说明确实发生了 3 次尝试（连接拒绝本身是瞬时的）。
        assertThat(elapsedMs).isGreaterThanOrEqualTo(250);
    }

    @Test
    void doesNotRetryInvalidJson() {
        worker.responder = request -> new StubResponse(200, "not-json", 0);

        assertThatThrownBy(() -> client("").rerank(
                request("混合架构", 2, candidate("c1", "标题一", "正文一"), candidate("c2", "标题二", "正文二"))))
                .isInstanceOfSatisfying(RerankGatewayException.class, failure ->
                        assertThat(failure.code()).isEqualTo(RerankGatewayErrorCode.SCHEMA_VIOLATION));
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void doesNotRetryWorkerDeadlineExceeded() {
        // 504 = worker 的 DEADLINE_EXCEEDED：deadline 已耗尽，同 deadline 重试只会放大用户等待。
        worker.responder = request -> new StubResponse(504, "", 0);

        assertThatThrownBy(() -> client("").rerank(
                request("混合架构", 2, candidate("c1", "标题一", "正文一"), candidate("c2", "标题二", "正文二"))))
                .isInstanceOfSatisfying(RerankGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(RerankGatewayErrorCode.DEADLINE_EXCEEDED);
                    assertThat(failure.isRetryable()).isFalse();
                });
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void rejectsNonV1SchemaVersion() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":2,\"scores\":[{\"id\":\"c1\",\"score\":0.5}]}", 0);

        assertThatThrownBy(() -> client("").rerank(
                request("混合架构", 1, candidate("c1", "标题一", "正文一"))))
                .isInstanceOfSatisfying(RerankGatewayException.class, failure ->
                        assertThat(failure.code()).isEqualTo(RerankGatewayErrorCode.SCHEMA_VIOLATION));
    }

    @Test
    void rejectsUnknownOrDuplicateScoreIds() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"scores\":[{\"id\":\"unknown\",\"score\":0.5}]}", 0);
        assertThatThrownBy(() -> client("").rerank(
                request("混合架构", 1, candidate("c1", "标题一", "正文一"))))
                .isInstanceOfSatisfying(RerankGatewayException.class, failure ->
                        assertThat(failure.code()).isEqualTo(RerankGatewayErrorCode.SCHEMA_VIOLATION));
    }

    @Test
    void doesNotRetryOn429() {
        worker.responder = request -> new StubResponse(429, "{}", 0);

        assertThatThrownBy(() -> client("").rerank(
                request("混合架构", 1, candidate("c1", "标题一", "正文一"))))
                .isInstanceOfSatisfying(RerankGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(RerankGatewayErrorCode.MODEL_RATE_LIMITED);
                    assertThat(failure.isRetryable()).isFalse();
                });
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void idempotencyKeyCoversCandidateText() {
        worker.responder = request -> new StubResponse(200, SUCCESS_BODY, 0);
        RerankGatewayClient client = client("");

        client.rerank(request("混合架构", 2, candidate("c1", "标题一", "旧正文"), candidate("c2", "标题二", "正文二")));
        client.rerank(request("混合架构", 2, candidate("c1", "标题一", "新正文"), candidate("c2", "标题二", "正文二")));
        client.rerank(request("混合架构", 2, candidate("c1", "标题一", "旧正文"), candidate("c2", "标题二", "正文二")));

        List<String> keys = worker.requests().stream().map(RecordedRequest::idempotencyKey).toList();
        assertThat(keys).hasSize(3);
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
        assertThat(keys.get(0)).isEqualTo(keys.get(2));
    }

    @Test
    void serializesNullTitleAsEmptyString() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"scores\":[{\"id\":\"c1\",\"score\":0.5}]}", 0);
        RerankGatewayClient client = client("");

        client.rerank(request("混合架构", 1, candidate("c1", null, "正文一")));

        assertThat(worker.requests().getFirst().body())
                .contains("\"title\":\"\"")
                .doesNotContain("\"title\":null");
    }

    @Test
    void propagatesTraceparentWhenMdcHasValidTraceContext() {
        worker.responder = request -> new StubResponse(200, SUCCESS_BODY, 0);
        MDC.put("traceId", "0123456789abcdef0123456789abcdef");
        MDC.put("spanId", "0123456789abcdef");

        client("").rerank(request("混合架构", 2,
                candidate("c1", "标题一", "正文一"), candidate("c2", "标题二", "正文二")));

        assertThat(worker.requests().getFirst().traceparent())
                .isEqualTo("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
    }

    @Test
    void classifiesTransportFailures() {
        assertThat(RerankGatewayClient.classifyTransportFailure(new HttpTimeoutException("read")).code())
                .isEqualTo(RerankGatewayErrorCode.DEADLINE_EXCEEDED);
        assertThat(RerankGatewayClient.classifyTransportFailure(new HttpTimeoutException("read")).isRetryable())
                .isFalse();

        RerankGatewayException connectTimeout = RerankGatewayClient.classifyTransportFailure(
                new HttpConnectTimeoutException("connect"));
        assertThat(connectTimeout.code()).isEqualTo(RerankGatewayErrorCode.MODEL_UNAVAILABLE);
        assertThat(connectTimeout.isRetryable()).isTrue();

        RerankGatewayException refused = RerankGatewayClient.classifyTransportFailure(
                new ConnectException("refused"));
        assertThat(refused.code()).isEqualTo(RerankGatewayErrorCode.MODEL_UNAVAILABLE);
        assertThat(refused.isRetryable()).isTrue();

        assertThat(RerankGatewayClient.classifyTransportFailure(new IllegalStateException("boom")).code())
                .isEqualTo(RerankGatewayErrorCode.INTERNAL);
    }

    @Test
    void parsesOptionalModelLatencyAndTreatsMissingOrInvalidAsUnknown() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"scores\":[{\"id\":\"c1\",\"score\":0.5}],\"modelLatencyMs\":1882}", 0);
        assertThat(client("").rerank(request("混合架构", 1, candidate("c1", "标题一", "正文一")))
                .modelLatencyMs()).isEqualTo(1882);

        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"scores\":[{\"id\":\"c1\",\"score\":0.5}],\"modelLatencyMs\":-1}", 0);
        assertThat(client("").rerank(request("混合架构", 1, candidate("c1", "标题一", "正文一")))
                .modelLatencyMs()).isNull();

        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"scores\":[{\"id\":\"c1\",\"score\":0.5}],\"modelLatencyMs\":\"1882\"}", 0);
        assertThat(client("").rerank(request("混合架构", 1, candidate("c1", "标题一", "正文一")))
                .modelLatencyMs()).isNull();

        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"scores\":[{\"id\":\"c1\",\"score\":0.5}]}", 0);
        assertThat(client("").rerank(request("混合架构", 1, candidate("c1", "标题一", "正文一")))
                .modelLatencyMs()).isNull();
    }

    @Test
    void recordsWorkerDurationSuccessAndNoRetryCounter() {
        worker.responder = request -> new StubResponse(200, SUCCESS_BODY, 0);

        client("").rerank(request("混合架构", 2, candidate("c1", "标题一", "正文一"),
                candidate("c2", "标题二", "正文二")));

        Timer success = registry.get("rerank.worker.duration").tag("outcome", "success").timer();
        assertThat(success.count()).isEqualTo(1);
        assertThat(success.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0.0);
        assertThat(registry.get("rerank.worker.duration").tag("outcome", "failure").timer().count()).isZero();
        assertThat(registry.get("rerank.worker.duration").tag("outcome", "retry_success").timer().count())
                .isZero();
        assertThat(retries("2")).isZero();
        assertThat(retries("3")).isZero();
    }

    @Test
    void recordsRetrySuccessDurationAndRetryAttemptCounters() {
        AtomicInteger attempts = new AtomicInteger();
        worker.responder = request -> attempts.incrementAndGet() <= 2
                ? new StubResponse(503, "{}", 0)
                : new StubResponse(200, SUCCESS_BODY, 0);

        client("").rerank(request("混合架构", 2, candidate("c1", "标题一", "正文一"),
                candidate("c2", "标题二", "正文二")));

        assertThat(registry.get("rerank.worker.duration").tag("outcome", "retry_success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find("rerank.worker.duration").tag("outcome", "success").timer().count())
                .isZero();
        assertThat(retries("2")).isEqualTo(1.0);
        assertThat(retries("3")).isEqualTo(1.0);
    }

    @Test
    void recordsFailureDurationWithoutRetryOn400() {
        worker.responder = request -> new StubResponse(400, "{\"code\":\"INVALID_REQUEST\"}", 0);

        assertThatThrownBy(() -> client("").rerank(
                request("混合架构", 1, candidate("c1", "标题一", "正文一"))))
                .isInstanceOf(RerankGatewayException.class);

        assertThat(registry.get("rerank.worker.duration").tag("outcome", "failure")
                .timer().count()).isEqualTo(1);
        assertThat(retries("2")).isZero();
        assertThat(retries("3")).isZero();
    }

    // ---------- helpers ----------

    private double retries(String attempt) {
        Counter counter = registry.find("rerank.worker.retries").tag("attempt", attempt).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private RerankGatewayClient client(String serviceToken) {
        RerankProperties properties = new RerankProperties(RerankMode.PYTHON, worker.baseUrl(), 1000,
                Duration.ofSeconds(2), serviceToken, 20, 8, 1.0, "");
        return new RerankGatewayClient(properties, new ObjectMapper(), registry);
    }

    private static RerankGatewayRequest request(String query, int topK,
            RerankGatewayRequest.Candidate... candidates) {
        return new RerankGatewayRequest(PROJECT_ID, query, topK, List.of(candidates), 1000);
    }

    private static RerankGatewayRequest.Candidate candidate(String id, String title, String text) {
        return new RerankGatewayRequest.Candidate(id, title, text);
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class StubWorker implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile Responder responder = request -> new StubResponse(200, SUCCESS_BODY, 0);

        private StubWorker() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "rerank-worker-stub");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/v1/rag/rerank", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RecordedRequest recorded = new RecordedRequest(
                        requests.size() + 1,
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                        exchange.getRequestHeaders().getFirst("X-Call-Id"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("traceparent"),
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
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(response.status(), payload.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(payload);
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
    }

    private record RecordedRequest(int index, String idempotencyKey, String callId,
            String authorization, String traceparent, String body) {
    }
}
