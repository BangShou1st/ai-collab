package com.shitulelv.aicollab.planning.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.PlanMilestone;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.domain.ValidationAssessment;
import com.shitulelv.aicollab.planning.domain.ValidationContext;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * W5 planning draft shadow 服务测试：java 模式零成本、shadow 触发一次、Python 草稿
 * 必须重跑真实 Validator、schema pass/fail/issue 指标、失败隔离与“绝不落库”边界。
 */
class PlanningDraftShadowServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PLAN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID MEMBER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String GOAL = "交付 v2";

    private final ObjectMapper json = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<PlanningDraftShadowService> services = new ArrayList<>();
    private final TaskPlanDraftValidator validator = new TaskPlanDraftValidator();

    private StubPlanningWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        registry.clear();
        worker = new StubPlanningWorker();
    }

    @AfterEach
    void tearDown() {
        services.forEach(PlanningDraftShadowService::shutdown);
        worker.close();
        registry.clear();
    }

    // ------------------------------------------------------------------
    // 默认零成本 / 触发
    // ------------------------------------------------------------------

    @Test
    void javaModePerformsZeroWorkAndRecordsZeroMetrics() {
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        PlanningDraftFactsSource facts = mock(PlanningDraftFactsSource.class);
        PlanningDraftShadowService service = service(properties(PlanningDraftMode.JAVA, 4, 48000),
                gateway, repository, facts);

        service.observe(plan(GOAL), javaDraft(), ValidationAssessment.empty());

        verifyNoInteractions(gateway, repository, facts);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void shadowTriggersOneBoundedCallAndRecordsPassMetrics() {
        worker.responder = request -> new StubResponse(200, passResponse());
        TaskPlanRepository repository = repository(context());
        PlanningDraftFactsSource facts = facts();
        PlanningDraftShadowService service = service(
                properties(PlanningDraftMode.SHADOW, 4, 48000),
                realGateway(properties(PlanningDraftMode.SHADOW, 4, 48000)), repository, facts);

        service.observe(plan(GOAL), javaDraft(), ValidationAssessment.empty());

        awaitUntil(() -> counter(PlanningDraftShadowService.METRIC_CALLS, "outcome", "success") == 1.0,
                3000);
        assertThat(counter(PlanningDraftShadowService.METRIC_SCHEMA_PASS, "result", "pass"))
                .isEqualTo(1.0);
        assertThat(summary("HARD").count()).isEqualTo(1);
        assertThat(summary("HARD").totalAmount()).isZero();
        assertThat(summary("SOFT").count()).isEqualTo(1);
        assertThat(summary("SOFT").totalAmount()).isEqualTo(2.0); // TASK_UNASSIGNED + 无来源
        assertThat(registry.get(PlanningDraftShadowService.METRIC_LATENCY)
                .tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(counter(PlanningDraftShadowService.METRIC_FAILURE, "reason", "timeout")).isZero();

        verify(repository).validationContext(any(TaskPlanRecord.class));
        verifyNoMoreInteractions(repository);

        assertThat(worker.requests()).hasSize(1);
        String body = worker.requests().getFirst().body();
        assertThat(body)
                .contains("\"goal\":\"" + GOAL + "\"")
                .contains("\"template\":\"ITERATION\"")
                .contains("\"projectName\":\"示例项目\"")
                .contains("\"members\":[{\"id\":\"" + MEMBER_ID)
                .contains("\"existingMilestoneTitles\":[\"里程碑 A\"]")
                .contains("\"existingTaskTitles\":[\"任务 A\"]")
                .contains("\"maxTasks\":20")
                .contains("\"maxMilestones\":8")
                .contains("\"startDate\":\"2026-11-01\"")
                .contains("\"dueDate\":\"2026-12-31\"")
                .contains("\"deadlineMs\":3000");
        assertThat(worker.requests().getFirst().authorization()).isEqualTo("Bearer test-token");
    }

    @Test
    void pythonDraftIsRevalidatedByRealValidatorAgainstProjectContext() {
        // 成员 UUID 不在 context.projectMemberIds() 中：只有真正重跑 Validator 才会判
        // ASSIGNEE_NOT_PROJECT_MEMBER（BLOCKING_EDITABLE）→ schema_pass=fail。
        worker.responder = request -> new StubResponse(200,
                responseWithAssignee(UUID.randomUUID().toString()));
        PlanningDraftShadowService service = service(
                properties(PlanningDraftMode.SHADOW, 4, 48000),
                realGateway(properties(PlanningDraftMode.SHADOW, 4, 48000)),
                repository(context()), facts());

        service.observe(plan(GOAL), javaDraft(), ValidationAssessment.empty());

        awaitUntil(() -> counter(PlanningDraftShadowService.METRIC_SCHEMA_PASS, "result", "fail")
                == 1.0, 3000);
        assertThat(counter(PlanningDraftShadowService.METRIC_CALLS, "outcome", "success"))
                .isEqualTo(1.0);
        assertThat(summary("SOFT").totalAmount()).isGreaterThanOrEqualTo(1.0);
        assertThat(counter(PlanningDraftShadowService.METRIC_SCHEMA_PASS, "result", "pass")).isZero();
    }

    @Test
    void emptyTasksFailSchemaPassWithHardIssueAndNeverBreakCaller() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"plan\":{\"milestones\":[],\"tasks\":[]},\"warnings\":[]}");
        PlanningDraftShadowService service = service(
                properties(PlanningDraftMode.SHADOW, 4, 48000),
                realGateway(properties(PlanningDraftMode.SHADOW, 4, 48000)),
                repository(context()), facts());

        assertThatCode(() -> service.observe(plan(GOAL), javaDraft(), ValidationAssessment.empty()))
                .doesNotThrowAnyException();

        awaitUntil(() -> counter(PlanningDraftShadowService.METRIC_SCHEMA_PASS, "result", "fail")
                == 1.0, 3000);
        assertThat(summary("HARD").totalAmount()).isGreaterThanOrEqualTo(2.0); // MILESTONE_REQUIRED + TASK_REQUIRED
        assertThat(counter(PlanningDraftShadowService.METRIC_CALLS, "outcome", "success"))
                .isEqualTo(1.0);
    }

    @Test
    void structurallyInvalidDraftIsSchemaInvalidWithoutThrowing() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"plan\":{\"milestones\":[{\"title\":\"缺少 tempKey\"}],"
                        + "\"tasks\":[]},\"warnings\":[]}");
        PlanningDraftShadowService service = service(
                properties(PlanningDraftMode.SHADOW, 4, 48000),
                realGateway(properties(PlanningDraftMode.SHADOW, 4, 48000)),
                repository(context()), facts());

        assertThatCode(() -> service.observe(plan(GOAL), javaDraft(), ValidationAssessment.empty()))
                .doesNotThrowAnyException();

        awaitUntil(() -> counter(PlanningDraftShadowService.METRIC_CALLS,
                "outcome", "schema_invalid") == 1.0, 3000);
        assertThat(counter(PlanningDraftShadowService.METRIC_SCHEMA_PASS, "result", "fail"))
                .isEqualTo(1.0);
        assertThat(counter(PlanningDraftShadowService.METRIC_FAILURE, "reason", "schema"))
                .isEqualTo(1.0);
    }

    @Test
    void gatewayFailureIsIsolatedAndCounted() {
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        when(gateway.draft(any())).thenThrow(new PlanningDraftGatewayException(
                PlanningDraftGatewayErrorCode.UNAVAILABLE, "worker down"));
        PlanningDraftShadowService service = service(
                properties(PlanningDraftMode.SHADOW, 4, 48000), gateway,
                repository(context()), facts());

        assertThatCode(() -> service.observe(plan(GOAL), javaDraft(), ValidationAssessment.empty()))
                .doesNotThrowAnyException();

        awaitUntil(() -> counter(PlanningDraftShadowService.METRIC_FAILURE,
                "reason", "unavailable") == 1.0, 3000);
        assertThat(counter(PlanningDraftShadowService.METRIC_CALLS, "outcome", "failure"))
                .isEqualTo(1.0);
        assertThat(registry.get(PlanningDraftShadowService.METRIC_LATENCY)
                .tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.find(PlanningDraftShadowService.METRIC_SCHEMA_PASS)
                .counter()).isNull();
    }

    @Test
    void saturatedConcurrencyDropsOnlyTheShadowCall() {
        worker.responder = request -> new StubResponse(200, passResponse());
        worker.requestStarted = new CountDownLatch(1);
        worker.responseGate = new CountDownLatch(1);
        PlanningDraftProperties properties = properties(PlanningDraftMode.SHADOW, 1, 48000);
        PlanningDraftShadowService service = service(properties, realGateway(properties),
                repository(context()), facts());

        service.observe(plan(GOAL), javaDraft(), ValidationAssessment.empty());
        try {
            assertThat(worker.requestStarted.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("等待 stub 请求被中断");
        }

        assertThatCode(() -> service.observe(plan(GOAL), javaDraft(), ValidationAssessment.empty()))
                .doesNotThrowAnyException();
        assertThat(counter(PlanningDraftShadowService.METRIC_SKIPPED, "reason", "saturated"))
                .isEqualTo(1.0);

        worker.responseGate.countDown();
        awaitUntil(() -> counter(PlanningDraftShadowService.METRIC_CALLS, "outcome", "success")
                == 1.0, 3000);
    }

    @Test
    void truncatesOversizedSnapshotAndStillSends() {
        worker.responder = request -> new StubResponse(200, passResponse());
        PlanningDraftProperties properties = properties(PlanningDraftMode.SHADOW, 4, 4000);
        PlanningDraftFactsSource facts = facts();
        when(facts.load(any(), any())).thenReturn(new PlanningDraftFactsSource.PlanFacts(
                "项目".repeat(100), "描述".repeat(2000),
                List.of(new PlanningDraftFactsSource.MemberFact(MEMBER_ID, "张三")),
                List.of("里程碑".repeat(100)), List.of("任务".repeat(100))));
        PlanningDraftShadowService service = service(properties, realGateway(properties),
                repository(context()), facts);

        service.observe(plan("目标".repeat(2000)), javaDraft(), ValidationAssessment.empty());

        awaitUntil(() -> worker.requests().size() == 1, 3000);
        assertThat(worker.requests().getFirst().body().length()).isLessThanOrEqualTo(4000);
        assertThat(counter(PlanningDraftShadowService.METRIC_TRUNCATED)).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void emptyGoalIsSkippedWithoutOutboundCall() {
        PlanningDraftGatewayClient gateway = mock(PlanningDraftGatewayClient.class);
        PlanningDraftShadowService service = service(
                properties(PlanningDraftMode.SHADOW, 4, 48000), gateway,
                repository(context()), facts());
        TaskPlanRecord blank = new TaskPlanRecord(PLAN_ID, PROJECT_ID, " ", " ",
                "{}", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31), 20, "[]",
                TaskPlanStatus.DETAIL_GENERATING, 1, null, 1L, UUID.randomUUID(), UUID.randomUUID(),
                null, null, OffsetDateTime.now(), OffsetDateTime.now());

        service.observe(blank, javaDraft(), ValidationAssessment.empty());

        verifyNoInteractions(gateway);
        assertThat(counter(PlanningDraftShadowService.METRIC_SKIPPED, "reason", "empty"))
                .isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private PlanningDraftShadowService service(PlanningDraftProperties properties,
            PlanningDraftGatewayClient gateway, TaskPlanRepository repository,
            PlanningDraftFactsSource facts) {
        PlanningDraftShadowService service = new PlanningDraftShadowService(properties, gateway,
                repository, facts, validator, json, registry);
        services.add(service);
        return service;
    }

    private PlanningDraftGatewayClient realGateway(PlanningDraftProperties properties) {
        return new PlanningDraftGatewayClient(properties, () -> "test-token", json);
    }

    private PlanningDraftProperties properties(PlanningDraftMode mode, int maxConcurrency,
            int snapshotMaxChars) {
        return new PlanningDraftProperties(mode, worker.baseUrl(), 3000, maxConcurrency,
                snapshotMaxChars);
    }

    private TaskPlanRepository repository(ValidationContext context) {
        TaskPlanRepository repository = mock(TaskPlanRepository.class);
        when(repository.validationContext(any())).thenReturn(context);
        return repository;
    }

    private static PlanningDraftFactsSource facts() {
        PlanningDraftFactsSource facts = mock(PlanningDraftFactsSource.class);
        when(facts.load(any(), any())).thenReturn(new PlanningDraftFactsSource.PlanFacts(
                "示例项目", "项目描述",
                List.of(new PlanningDraftFactsSource.MemberFact(MEMBER_ID, "张三")),
                List.of("里程碑 A"), List.of("任务 A")));
        return facts;
    }

    private static ValidationContext context() {
        return new ValidationContext(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31),
                20, Set.of(MEMBER_ID), Set.of());
    }

    private static TaskPlanRecord plan(String goal) {
        return new TaskPlanRecord(PLAN_ID, PROJECT_ID, "迭代计划", goal,
                "{}", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31), 20, "[]",
                TaskPlanStatus.DETAIL_GENERATING, 1, null, 1L, UUID.randomUUID(), UUID.randomUUID(),
                null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static TaskPlanDraft javaDraft() {
        return new TaskPlanDraft("Java 草稿", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "里程碑", "目标", "描述",
                        LocalDate.of(2026, 12, 1), 0, List.of())),
                List.of(new PlanTask("t1", "m1", "任务", "目标", "描述", "MEDIUM",
                        BigDecimal.valueOf(8), LocalDate.of(2026, 11, 5),
                        LocalDate.of(2026, 11, 20), MEMBER_ID, null, List.of(), List.of(), 0)),
                List.of());
    }

    private static String passResponse() {
        return responseWithAssignee(MEMBER_ID.toString());
    }

    private static String responseWithAssignee(String assigneeId) {
        return """
                {"schemaVersion":1,
                 "plan":{"milestones":[{"tempKey":"m1","title":"里程碑","objective":"交付",
                   "description":"说明","targetDate":"2026-12-01","sourceRefs":[],"sortOrder":0}],
                   "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"任务","objective":"完成",
                     "description":"详情","priority":"MEDIUM","estimatedHours":8,
                     "startDate":"2026-11-05","dueDate":"2026-11-20",
                     "assigneeId":"%s","dependencyTempKeys":[],"sourceRefs":[],"sortOrder":0}]},
                 "warnings":[],
                 "usage":{"inputTokens":100,"outputTokens":60,"source":"PROVIDER"}}
                """.formatted(assigneeId);
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private DistributionSummary summary(String severity) {
        return registry.get(PlanningDraftShadowService.METRIC_ISSUES)
                .tag("severity", severity).summary();
    }

    private static void awaitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("等待被中断");
            }
        }
        fail("条件在 " + timeoutMs + "ms 内未满足");
    }

    private static final class StubPlanningWorker implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile Responder responder = request -> new StubResponse(200, passResponse());
        private volatile CountDownLatch requestStarted = new CountDownLatch(0);
        private volatile CountDownLatch responseGate;

        private StubPlanningWorker() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "planning-draft-shadow-stub");
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
                requestStarted.countDown();
                CountDownLatch gate = responseGate;
                if (gate != null) {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
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
                    // 关闭 stub 时客户端可能已断开，属预期噪声。
                }
            });
            server.start();
        }

        private List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
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
