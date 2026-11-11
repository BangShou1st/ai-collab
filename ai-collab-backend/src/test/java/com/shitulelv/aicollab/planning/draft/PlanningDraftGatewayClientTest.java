package com.shitulelv.aicollab.planning.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Planning draft 网关客户端契约测试（真实本地 {@link HttpServer} stub）：
 * 请求字段/鉴权、严格响应解析、HTTP/超时分类与“绝不重试”。
 */
class PlanningDraftGatewayClientTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private StubPlanningWorker worker;

    @BeforeEach
    void startStub() throws IOException {
        worker = new StubPlanningWorker();
    }

    @AfterEach
    void stopStub() {
        worker.close();
    }

    @Test
    void parsesSuccessfulResponseAndSendsContractBodyAndBearer() {
        worker.responder = request -> new StubResponse(200, validResponse());

        PlanningDraftResponse response = client("test-token").draft(request(60000));

        assertThat(response.schemaVersion()).isEqualTo(1);
        assertThat(response.plan().path("milestones")).hasSize(1);
        assertThat(response.plan().path("tasks")).hasSize(1);
        assertThat(response.warnings()).containsExactly("已按项目日期收敛");
        assertThat(response.usage().inputTokens()).isEqualTo(8123);
        assertThat(response.usage().outputTokens()).isEqualTo(640);
        assertThat(response.usage().source()).isEqualTo("PROVIDER");

        assertThat(worker.requests()).hasSize(1);
        RecordedRequest recorded = worker.requests().getFirst();
        assertThat(recorded.authorization()).isEqualTo("Bearer test-token");
        assertThat(recorded.body())
                .contains("\"schemaVersion\":1")
                .contains("\"goal\":\"交付 v2\"")
                .contains("\"locale\":\"zh-CN\"")
                .contains("\"projectName\":\"示例项目\"")
                .contains("\"description\":\"项目描述\"")
                .contains("\"members\":[{\"id\":\"" + request(60000).projectFacts().members().getFirst().id()
                        + "\",\"name\":\"张三\"}]")
                .contains("\"existingMilestoneTitles\":[\"里程碑 A\"]")
                .contains("\"existingTaskTitles\":[\"任务 A\"]")
                .contains("\"template\":\"ITERATION\"")
                .contains("\"maxTasks\":20")
                .contains("\"maxMilestones\":6")
                .contains("\"startDate\":\"2026-11-01\"")
                .contains("\"dueDate\":\"2026-12-31\"")
                .contains("\"deadlineMs\":60000");
    }

    @Test
    void omitsAuthorizationWhenServiceTokenIsEmpty() {
        worker.responder = request -> new StubResponse(200, validResponse());

        client("").draft(request(1000));

        assertThat(worker.requests().getFirst().authorization()).isNull();
    }

    @Test
    void rejectsInvalidJsonAndContractViolationsWithoutRetry() {
        worker.responder = request -> new StubResponse(200, "not-json");
        assertThatThrownBy(() -> client("").draft(request(1000)))
                .isInstanceOfSatisfying(PlanningDraftGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(PlanningDraftGatewayErrorCode.SCHEMA));
        assertThat(worker.requests()).hasSize(1);

        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":2,\"plan\":{\"milestones\":[],\"tasks\":[]}}");
        assertThatThrownBy(() -> client("").draft(request(1000)))
                .isInstanceOfSatisfying(PlanningDraftGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(PlanningDraftGatewayErrorCode.SCHEMA));

        worker.responder = request -> new StubResponse(200, "{\"schemaVersion\":1}");
        assertThatThrownBy(() -> client("").draft(request(1000)))
                .isInstanceOfSatisfying(PlanningDraftGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(PlanningDraftGatewayErrorCode.SCHEMA));

        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"plan\":{\"milestones\":[],\"tasks\":[]},\"warnings\":\"x\"}");
        assertThatThrownBy(() -> client("").draft(request(1000)))
                .isInstanceOfSatisfying(PlanningDraftGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(PlanningDraftGatewayErrorCode.SCHEMA));
        assertThat(worker.requests()).hasSize(4);
    }

    @Test
    void mapsHttpStatusesWithoutRetry() {
        worker.responder = request -> new StubResponse(503, "{}");
        assertThatThrownBy(() -> client("").draft(request(1000)))
                .isInstanceOfSatisfying(PlanningDraftGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(PlanningDraftGatewayErrorCode.UNAVAILABLE));
        assertThat(worker.requests()).hasSize(1);

        worker.responder = request -> new StubResponse(504, "{}");
        assertThatThrownBy(() -> client("").draft(request(1000)))
                .isInstanceOfSatisfying(PlanningDraftGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(PlanningDraftGatewayErrorCode.TIMEOUT));
        assertThat(worker.requests()).hasSize(2);

        worker.responder = request -> new StubResponse(422, "{}");
        assertThatThrownBy(() -> client("").draft(request(1000)))
                .isInstanceOfSatisfying(PlanningDraftGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(PlanningDraftGatewayErrorCode.HTTP));
        assertThat(worker.requests()).hasSize(3);
    }

    @Test
    void classifiesReadTimeoutWithShortGrace() {
        worker.responder = request -> new StubResponse(200, validResponse(), 500);
        PlanningDraftProperties properties = properties(worker.baseUrl(), 100);
        PlanningDraftGatewayClient shortTimeoutClient = new PlanningDraftGatewayClient(properties,
                () -> "", json, HttpClient.newBuilder().build(), Duration.ofMillis(150));

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> shortTimeoutClient.draft(request(100)))
                .isInstanceOfSatisfying(PlanningDraftGatewayException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(PlanningDraftGatewayErrorCode.TIMEOUT));
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(3000);
        assertThat(worker.requests()).hasSize(1);
    }

    @Test
    void missingUsageAndWarningsBecomeUnknownAndEmpty() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"plan\":{\"milestones\":[],\"tasks\":[]}}");

        PlanningDraftResponse response = client("").draft(request(1000));

        assertThat(response.warnings()).isEmpty();
        assertThat(response.usage().inputTokens()).isNull();
        assertThat(response.usage().outputTokens()).isNull();
        assertThat(response.usage().source()).isEqualTo("UNKNOWN");
    }

    @Test
    void classifiesTransportFailures() {
        assertThat(PlanningDraftGatewayClient.classifyTransportFailure(
                new java.net.http.HttpTimeoutException("read")).reason())
                .isEqualTo(PlanningDraftGatewayErrorCode.TIMEOUT);
        assertThat(PlanningDraftGatewayClient.classifyTransportFailure(
                new java.net.http.HttpConnectTimeoutException("connect")).reason())
                .isEqualTo(PlanningDraftGatewayErrorCode.TIMEOUT);
        assertThat(PlanningDraftGatewayClient.classifyTransportFailure(
                new java.net.ConnectException("refused")).reason())
                .isEqualTo(PlanningDraftGatewayErrorCode.UNAVAILABLE);
        assertThat(PlanningDraftGatewayClient.classifyTransportFailure(
                new IOException("io")).reason()).isEqualTo(PlanningDraftGatewayErrorCode.UNAVAILABLE);
        assertThat(PlanningDraftGatewayClient.classifyTransportFailure(
                new IllegalStateException("boom")).reason())
                .isEqualTo(PlanningDraftGatewayErrorCode.UNEXPECTED);
    }

    // ---------- helpers ----------

    private PlanningDraftGatewayClient client(String serviceToken) {
        return new PlanningDraftGatewayClient(properties(worker.baseUrl(), 60000),
                () -> serviceToken, json);
    }

    private static PlanningDraftProperties properties(String baseUrl, int deadlineMs) {
        return new PlanningDraftProperties(PlanningDraftMode.SHADOW, baseUrl, deadlineMs, 4, 48000);
    }

    private static PlanningDraftRequest request(int deadlineMs) {
        return new PlanningDraftRequest(
                1,
                "交付 v2",
                "zh-CN",
                new PlanningDraftRequest.ProjectFacts(
                        "示例项目",
                        "项目描述",
                        List.of(new PlanningDraftRequest.Member(
                                "11111111-1111-1111-1111-111111111111", "张三")),
                        List.of("里程碑 A"),
                        List.of("任务 A")),
                "ITERATION",
                new PlanningDraftRequest.Constraints(20, 6, "2026-11-01", "2026-12-31"),
                deadlineMs);
    }

    private static String validResponse() {
        return """
                {"schemaVersion":1,
                 "plan":{"milestones":[{"tempKey":"m1","title":"里程碑","objective":"交付",
                   "description":"说明","targetDate":"2026-12-01","sourceRefs":[],"sortOrder":0}],
                   "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"任务","objective":"完成",
                     "description":"详情","priority":"MEDIUM","estimatedHours":8,
                     "startDate":"2026-11-20","dueDate":"2026-11-27",
                     "assigneeId":null,"dependencyTempKeys":[],"sourceRefs":[],"sortOrder":0}]},
                 "warnings":["已按项目日期收敛"],
                 "usage":{"inputTokens":8123,"outputTokens":640,"source":"PROVIDER"}}
                """;
    }

    private static final class StubPlanningWorker implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile Responder responder = request -> new StubResponse(200, validResponse());

        private StubPlanningWorker() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "planning-draft-worker-stub");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext(PlanningDraftGatewayClient.PLANNING_DRAFT_PATH, exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                RecordedRequest recorded = new RecordedRequest(
                        exchange.getRequestHeaders().getFirst("Authorization"), body);
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

    private record RecordedRequest(String authorization, String body) {
    }
}
