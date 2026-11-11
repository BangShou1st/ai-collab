package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.domain.model.builtin.ProjectHealthScenario;
import com.shitulelv.aicollab.agent.domain.model.builtin.ProjectResearchScenario;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideDecision;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayClient;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayResponse;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideMode;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideProperties;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.retrieval.rerank.RerankMode;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W4 decide shadow 服务测试：触发/不触发矩阵、快照字段与稳定哈希、失败隔离、
 * 一致率/契约/无效工具调用指标，以及"Python 结果绝不进入执行/落库"的边界。
 *
 * <p>网关行为用真实本地 {@link HttpServer} stub 覆盖；需要精确控制响应或并发额度时
 * 使用 mock 网关（两种方式都不产生真实外呼）。</p>
 */
class AgentDecideShadowServiceTest {

    private static final String TOOL = "search_project_knowledge";
    private static final String SECOND_READ_TOOL = "get_project_overview";
    private static final String WRITE_TOOL = "create_task";

    private final ObjectMapper json = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<AgentDecideShadowService> services = new ArrayList<>();

    private StubDecideWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        registry.clear();
        worker = new StubDecideWorker();
    }

    @AfterEach
    void tearDown() {
        services.forEach(AgentDecideShadowService::shutdown);
        worker.close();
        registry.clear();
    }

    // ------------------------------------------------------------------
    // 触发矩阵
    // ------------------------------------------------------------------

    @Test
    void shadowObservesReadOnlyProjectResearchToolTurnWithBoundedSnapshot() {
        worker.responder = request -> new StubResponse(200, callToolResponse(TOOL));
        AgentDecideShadowService service = shadowService(DecideMode.SHADOW, 8, 32000);
        AgentRunView run = run(0, "梳理项目风险");
        List<AgentToolDefinition> exposed = List.of(
                AgentToolDefinition.openObject(TOOL, "检索项目知识", false),
                AgentToolDefinition.openObject(WRITE_TOOL, "创建任务", true));
        List<AgentStepView> steps = List.of(toolCompletedStep(1, TOOL,
                "{\"status\":\"SUCCEEDED\",\"data\":{\"summary\":\"检索到 2 条证据\","
                        + "\"taskFacts\":[\"任务 A 未完成\"]}}"));

        service.observe(run, new ProjectResearchScenario(), toolTurn(TOOL), exposed, steps);

        awaitUntil(() -> counter(AgentDecideShadowService.METRIC_CALLS,
                "decision", "CALL_TOOL") == 1.0, 3000);
        assertThat(counter(AgentDecideShadowService.METRIC_AGREEMENT, "result", "match"))
                .isEqualTo(1.0);
        assertThat(counter(AgentDecideShadowService.METRIC_CONTRACT, "result", "valid"))
                .isEqualTo(1.0);
        assertThat(registry.get(AgentDecideShadowService.METRIC_LATENCY).tag("outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(counter(AgentDecideShadowService.METRIC_INVALID_TOOL_CALL)).isZero();
        assertThat(counter(AgentDecideShadowService.METRIC_SKIPPED, "reason", "turn")).isZero();

        assertThat(worker.requests()).hasSize(1);
        String body = worker.requests().getFirst().body();
        assertThat(body)
                .contains("\"runId\":\"" + run.id() + "\"")
                .contains("\"attempt\":1")
                .contains("\"turnNo\":4")
                .contains("\"snapshotHash\":\"sha256:")
                .contains("\"scenario\":{\"code\":\"PROJECT_RESEARCH\",\"category\":\"SCENARIO\"}")
                .contains("\"goal\":\"梳理项目风险\"")
                .contains("\"locale\":\"zh-CN\"")
                .contains("\"facts\":[\"任务 A 未完成\"]")
                .contains("\"messages\":[]")
                .contains("\"observations\":[{\"tool\":\"" + TOOL
                        + "\",\"summary\":\"检索到 2 条证据\"}]")
                .contains("\"maxInputTokens\":24000")
                .contains("\"maxOutputTokens\":1000")
                .contains("\"deadlineMs\":3000")
                .contains("\"allowWrite\":false")
                .contains("\"allowExternal\":false");
        // 快照 tools 只含只读工具，按 name 排序；写工具绝不下发。
        assertThat(body).contains("\"tools\":[{\"name\":\"" + TOOL + "\"");
        assertThat(body).doesNotContain(WRITE_TOOL);
    }

    @Test
    void javaModePerformsZeroWorkAndRecordsZeroMetrics() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        AgentDecideShadowService service = new AgentDecideShadowService(
                properties(DecideMode.JAVA, 8, 32000), gateway, json, registry);

        service.observe(run(0, "梳理项目风险"), new ProjectResearchScenario(), toolTurn(TOOL),
                readOnlyExposed(TOOL), List.of());

        verifyNoInteractions(gateway);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void skipsWriteScenarioNonTargetScenarioAndChildRun() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        AgentDecideShadowService service = new AgentDecideShadowService(
                properties(DecideMode.SHADOW, 8, 32000), gateway, json, registry);

        service.observe(run(0, "写操作"), scenario("PROJECT_RESEARCH", true), toolTurn(TOOL),
                List.of(AgentToolDefinition.openObject(TOOL, "检索", false)), List.of());
        service.observe(run(0, "健康检查"), new ProjectHealthScenario(), toolTurn(TOOL),
                readOnlyExposed(TOOL), List.of());
        service.observe(run(1, "子研究"), new ProjectResearchScenario(), toolTurn(TOOL),
                readOnlyExposed(TOOL), List.of());

        verifyNoInteractions(gateway);
        assertThat(counter(AgentDecideShadowService.METRIC_SKIPPED, "reason", "scenario"))
                .isEqualTo(3.0);
        assertThat(counter(AgentDecideShadowService.METRIC_SKIPPED, "reason", "turn")).isZero();
    }

    @Test
    void skipsPureTextTurnUnknownToolAndWritingTool() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        AgentDecideShadowService service = new AgentDecideShadowService(
                properties(DecideMode.SHADOW, 8, 32000), gateway, json, registry);

        service.observe(run(0, "回答"), new ProjectResearchScenario(), textTurn("最终答案"),
                readOnlyExposed(TOOL), List.of());
        service.observe(run(0, "未知工具"), new ProjectResearchScenario(), toolTurn("missing_tool"),
                readOnlyExposed(TOOL), List.of());
        service.observe(run(0, "写工具"), new ProjectResearchScenario(), toolTurn(WRITE_TOOL),
                List.of(AgentToolDefinition.openObject(WRITE_TOOL, "创建任务", true)), List.of());

        verifyNoInteractions(gateway);
        assertThat(counter(AgentDecideShadowService.METRIC_SKIPPED, "reason", "turn"))
                .isEqualTo(3.0);
    }

    @Test
    void skipsWhenSnapshotCannotCarryCalledTool() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        AgentDecideShadowService service = new AgentDecideShadowService(
                properties(DecideMode.SHADOW, 8, 32000), gateway, json, registry);

        // 被调用工具的 schema 超过 2000 字符：快照 tools 无法包含它，Python 不可能返回合法调用。
        AgentToolDefinition hugeSchema = AgentToolDefinition.fromJson(TOOL, "超大 schema",
                "{\"type\":\"object\",\"description\":\"" + "x".repeat(2500) + "\"}", false);

        service.observe(run(0, "超大 schema"), new ProjectResearchScenario(), toolTurn(TOOL),
                List.of(hugeSchema), List.of());

        verifyNoInteractions(gateway);
        assertThat(counter(AgentDecideShadowService.METRIC_SKIPPED, "reason", "turn"))
                .isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // 指标
    // ------------------------------------------------------------------

    @Test
    void recordsMismatchWhenPythonDisagrees() {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"FINAL\"}");
        AgentDecideShadowService service = shadowService(DecideMode.SHADOW, 8, 32000);

        service.observe(run(0, "梳理项目风险"), new ProjectResearchScenario(), toolTurn(TOOL),
                readOnlyExposed(TOOL), List.of());

        awaitUntil(() -> counter(AgentDecideShadowService.METRIC_CALLS, "decision", "FINAL") == 1.0, 3000);
        assertThat(counter(AgentDecideShadowService.METRIC_AGREEMENT, "result", "mismatch"))
                .isEqualTo(1.0);
        assertThat(counter(AgentDecideShadowService.METRIC_CONTRACT, "result", "valid"))
                .isEqualTo(1.0);
    }

    @Test
    void recordsContractInvalidAndFailureOnMalformedResponse() {
        worker.responder = request -> new StubResponse(200, "not-json");
        AgentDecideShadowService service = shadowService(DecideMode.SHADOW, 8, 32000);

        service.observe(run(0, "梳理项目风险"), new ProjectResearchScenario(), toolTurn(TOOL),
                readOnlyExposed(TOOL), List.of());

        awaitUntil(() -> counter(AgentDecideShadowService.METRIC_FAILURE, "reason", "schema") == 1.0, 3000);
        assertThat(counter(AgentDecideShadowService.METRIC_CONTRACT, "result", "invalid"))
                .isEqualTo(1.0);
        assertThat(registry.get(AgentDecideShadowService.METRIC_LATENCY).tag("outcome", "failure")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find(AgentDecideShadowService.METRIC_CALLS).counter()).isNull();
    }

    @Test
    void recordsUnavailableFailureAndNeverBreaksCaller() {
        worker.responder = request -> new StubResponse(503, "{}");
        AgentDecideShadowService service = shadowService(DecideMode.SHADOW, 8, 32000);

        assertThatCode(() -> service.observe(run(0, "梳理项目风险"),
                new ProjectResearchScenario(), toolTurn(TOOL), readOnlyExposed(TOOL), List.of()))
                .doesNotThrowAnyException();

        awaitUntil(() -> counter(AgentDecideShadowService.METRIC_FAILURE, "reason", "unavailable")
                == 1.0, 3000);
        assertThat(counter(AgentDecideShadowService.METRIC_CONTRACT, "result", "invalid"))
                .isEqualTo(1.0);
    }

    @Test
    void invalidToolCallIsCountedDefensively() {
        DecideGatewayClient gateway = mock(DecideGatewayClient.class);
        when(gateway.decide(any())).thenReturn(new DecideGatewayResponse(
                1, DecideDecision.CALL_TOOL,
                new DecideGatewayResponse.ToolCall("delete_everything", json.createObjectNode()),
                DecideGatewayResponse.Usage.unknown(), null, null, null));
        AgentDecideShadowService service = new AgentDecideShadowService(
                properties(DecideMode.SHADOW, 8, 32000), gateway, json, registry);

        service.observe(run(0, "梳理项目风险"), new ProjectResearchScenario(), toolTurn(TOOL),
                readOnlyExposed(TOOL), List.of());

        awaitUntil(() -> counter(AgentDecideShadowService.METRIC_INVALID_TOOL_CALL) == 1.0, 3000);
        assertThat(counter(AgentDecideShadowService.METRIC_CALLS, "decision", "CALL_TOOL"))
                .isEqualTo(1.0);
        assertThat(counter(AgentDecideShadowService.METRIC_AGREEMENT, "result", "mismatch"))
                .isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // 非阻塞 / 稳定性 / 截断 / 饱和
    // ------------------------------------------------------------------

    @Test
    void observeReturnsImmediatelyWhenWorkerIsSlow() throws Exception {
        worker.responder = request -> new StubResponse(200, callToolResponse(TOOL), 300);
        AgentDecideShadowService service = shadowService(DecideMode.SHADOW, 8, 32000);

        long startNanos = System.nanoTime();
        service.observe(run(0, "梳理项目风险"), new ProjectResearchScenario(), toolTurn(TOOL),
                readOnlyExposed(TOOL), List.of());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(elapsedMs).isLessThan(200);
        awaitUntil(() -> counter(AgentDecideShadowService.METRIC_CONTRACT, "result", "valid")
                == 1.0, 3000);
    }

    @Test
    void snapshotHashIsStableForSameInputAndChangesWithDifferentInput() throws Exception {
        worker.responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"FINAL\"}");
        AgentDecideShadowService service = shadowService(DecideMode.SHADOW, 8, 32000);
        AgentRunView run = run(0, "梳理项目风险");
        ProjectResearchScenario scenario = new ProjectResearchScenario();
        ModelTurnResult turn = toolTurn(TOOL);
        List<AgentToolDefinition> exposed = readOnlyExposed(TOOL);
        List<AgentStepView> steps = List.of();

        service.observe(run, scenario, turn, exposed, steps);
        awaitRequestCount(1);
        service.observe(run, scenario, turn, exposed, steps);
        awaitRequestCount(2);

        String first = snapshotHash(worker.requests().get(0).body());
        String second = snapshotHash(worker.requests().get(1).body());
        assertThat(first).matches("sha256:[0-9a-f]{32}");
        assertThat(second).isEqualTo(first);

        service.observe(run(0, "另一个目标"), scenario, turn, exposed, steps);
        awaitRequestCount(3);
        assertThat(snapshotHash(worker.requests().get(2).body())).isNotEqualTo(first);
    }

    @Test
    void truncatesSnapshotToConfiguredCharLimit() {
        worker.responder = request -> new StubResponse(200, callToolResponse(TOOL));
        AgentDecideShadowService service = shadowService(DecideMode.SHADOW, 8, 4000);
        AgentRunView run = run(0, "目标".repeat(1000));
        List<AgentStepView> steps = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            steps.add(toolCompletedStep(index + 1, TOOL,
                    "{\"status\":\"SUCCEEDED\",\"data\":{\"taskFacts\":[\""
                            + "事实".repeat(250) + "\"]}}"));
        }
        List<AgentToolDefinition> exposed = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            exposed.add(AgentToolDefinition.fromJson("tool_" + index, "工具 " + index,
                    "{\"type\":\"object\",\"description\":\"" + "y".repeat(400) + "\"}", false));
        }
        exposed.add(AgentToolDefinition.openObject(TOOL, "检索项目知识", false));

        service.observe(run, new ProjectResearchScenario(), toolTurn(TOOL), exposed, steps);

        awaitRequestCount(1);
        String body = worker.requests().getFirst().body();
        assertThat(body.length()).isLessThanOrEqualTo(4000);
        assertThat(counter(AgentDecideShadowService.METRIC_TRUNCATED)).isGreaterThanOrEqualTo(1.0);
        assertThat(body).contains("\"snapshotHash\":\"sha256:");
    }

    @Test
    void saturatedConcurrencyDropsOnlyTheShadowCall() throws Exception {
        worker.responder = request -> new StubResponse(200, callToolResponse(TOOL));
        worker.requestStarted = new CountDownLatch(1);
        worker.responseGate = new CountDownLatch(1);
        AgentDecideShadowService service = shadowService(DecideMode.SHADOW, 1, 32000);
        AgentRunView run = run(0, "梳理项目风险");

        service.observe(run, new ProjectResearchScenario(), toolTurn(TOOL),
                readOnlyExposed(TOOL), List.of());
        assertThat(worker.requestStarted.await(3, TimeUnit.SECONDS)).isTrue();

        assertThatCode(() -> service.observe(run, new ProjectResearchScenario(), toolTurn(TOOL),
                readOnlyExposed(TOOL), List.of())).doesNotThrowAnyException();
        assertThat(counter(AgentDecideShadowService.METRIC_SKIPPED, "reason", "saturated"))
                .isEqualTo(1.0);

        worker.responseGate.countDown();
        awaitUntil(() -> counter(AgentDecideShadowService.METRIC_CALLS, "decision", "CALL_TOOL")
                == 1.0, 3000);
        assertThat(worker.requests()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 边界：Python 结果绝不进入执行/落库
    // ------------------------------------------------------------------

    @Test
    void pythonResultNeverTriggersExecutionOrPersistence() {
        worker.responder = request -> new StubResponse(200, callToolResponse(TOOL));
        AgentDecideShadowService service = shadowService(DecideMode.SHADOW, 8, 32000);
        AgentRepository repository = mock(AgentRepository.class);
        AgentRunEventRecorder events = mock(AgentRunEventRecorder.class);
        var toolExecutor = mock(com.shitulelv.aicollab.agent.application.runtime.AgentToolCallExecutor.class);
        AgentRunView run = run(0, "梳理项目风险");
        ModelTurnResult turn = toolTurn(TOOL);
        List<AgentToolDefinition> exposed = readOnlyExposed(TOOL);
        List<AgentStepView> steps = List.of();

        service.observe(run, new ProjectResearchScenario(), turn, exposed, steps);
        awaitUntil(() -> counter(AgentDecideShadowService.METRIC_CALLS, "decision", "CALL_TOOL")
                == 1.0, 3000);

        verifyNoInteractions(repository, events, toolExecutor);
        // 入参记录不可变且未被改写。
        assertThat(run.goal()).isEqualTo("梳理项目风险");
        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(exposed).hasSize(1);
        assertThat(steps).isEmpty();
        // 构造边界：服务根本没有执行器/仓储/事件协作者，后续也不得注入。
        assertThat(Arrays.stream(AgentDecideShadowService.class.getDeclaredFields()).map(Field::getType))
                .noneMatch(type -> AgentRepository.class.isAssignableFrom(type)
                        || AgentRunEventRecorder.class.isAssignableFrom(type)
                        || AgentToolCallExecutor.class.isAssignableFrom(type));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private AgentDecideShadowService shadowService(DecideMode mode, int maxConcurrency,
            int snapshotMaxChars) {
        DecideProperties properties = properties(mode, maxConcurrency, snapshotMaxChars);
        AgentDecideShadowService service = new AgentDecideShadowService(properties,
                new DecideGatewayClient(properties, rerankProperties("test-token"), json), json, registry);
        services.add(service);
        return service;
    }

    private DecideProperties properties(DecideMode mode, int maxConcurrency, int snapshotMaxChars) {
        return new DecideProperties(mode, worker.baseUrl(), 3000, maxConcurrency, 16,
                snapshotMaxChars);
    }

    private static RerankProperties rerankProperties(String serviceToken) {
        return new RerankProperties(RerankMode.SHADOW, "http://127.0.0.1:1", 1000,
                Duration.ofSeconds(2), serviceToken, 20, 8, 1.0, "");
    }

    private static AgentRunView run(int depth, String goal) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                depth > 0 ? UUID.randomUUID() : null, "OWNER", depth, goal, AgentRunStatus.RUNNING,
                20, 40, 4, null, null, 3, 1, 0, 100, 50, 100L, 50L, false, false, false,
                0, null, null, null, "PROJECT_RESEARCH", 1, AgentResourcePolicy.V2, false, now, now);
    }

    private static ModelTurnResult toolTurn(String... toolNames) {
        ObjectMapper mapper = new ObjectMapper();
        List<ModelToolCall> calls = new ArrayList<>();
        for (int index = 0; index < toolNames.length; index++) {
            calls.add(new ModelToolCall("call-" + index, toolNames[index],
                    mapper.createObjectNode().put("query", "项目风险")));
        }
        return new ModelTurnResult("", calls, ModelFinishReason.TOOL_CALLS, null,
                "OPENAI_COMPATIBLE", "test-model", 10L);
    }

    private static ModelTurnResult textTurn(String content) {
        return new ModelTurnResult(content, List.of(), ModelFinishReason.STOP, null,
                "OPENAI_COMPATIBLE", "test-model", 10L);
    }

    private static List<AgentToolDefinition> readOnlyExposed(String... names) {
        List<AgentToolDefinition> exposed = new ArrayList<>();
        for (String name : names) {
            exposed.add(AgentToolDefinition.openObject(name, "只读工具 " + name, false));
        }
        return List.copyOf(exposed);
    }

    private static AgentScenario scenario(String code, boolean allowWrite) {
        return new AgentScenario() {
            @Override public String code() { return code; }
            @Override public String displayName() { return "测试场景"; }
            @Override public String description() { return "测试场景"; }
            @Override public java.util.Set<String> entryRoutes() { return java.util.Set.of(); }
            @Override public java.util.Set<String> grantedTools() { return java.util.Set.of(); }
            @Override public JsonNode inputSchema() {
                return new ObjectMapper().createObjectNode().put("type", "object");
            }
            @Override public boolean allowWriteTools() { return allowWrite; }
        };
    }

    private static AgentStepView toolCompletedStep(int sequence, String toolName, String outputJson) {
        try {
            JsonNode output = new ObjectMapper().readTree(outputJson);
            return new AgentStepView(UUID.randomUUID(), sequence, AgentStepType.TOOL_CALL_COMPLETED,
                    toolName, new ObjectMapper().createObjectNode(), output, "TOOL_SUCCESS",
                    100, 10, false, 5, null, OffsetDateTime.now());
        } catch (IOException malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private static String callToolResponse(String toolName) {
        return "{\"schemaVersion\":1,\"decision\":\"CALL_TOOL\",\"toolCall\":{\"name\":\""
                + toolName + "\",\"arguments\":{\"query\":\"项目风险\"}},"
                + "\"usage\":{\"inputTokens\":100,\"outputTokens\":10,\"source\":\"PROVIDER\"},"
                + "\"promptVersion\":\"2026-11-11.1\",\"modelAlias\":\"agent-main\",\"latencyMs\":12}";
    }

    private static String snapshotHash(String body) throws IOException {
        return new ObjectMapper().readTree(body).path("snapshotHash").asText();
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void awaitRequestCount(int expected) {
        awaitUntil(() -> worker.requests().size() >= expected, 3000);
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

    private static final class StubDecideWorker implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile Responder responder = request -> new StubResponse(200,
                "{\"schemaVersion\":1,\"decision\":\"FINAL\"}");
        private volatile CountDownLatch requestStarted = new CountDownLatch(0);
        private volatile CountDownLatch responseGate;

        private StubDecideWorker() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "decide-shadow-service-stub");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/v1/agent/decide", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                requests.add(new RecordedRequest(body));
                requestStarted.countDown();
                CountDownLatch gate = responseGate;
                if (gate != null) {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                StubResponse response = responder.respond(new RecordedRequest(body));
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
                    // 慢响应测试关闭 stub 时客户端可能已断开，属预期噪声。
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

    private record RecordedRequest(String body) {
    }
}
