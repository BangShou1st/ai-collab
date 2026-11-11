package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;
import com.shitulelv.aicollab.agent.domain.model.AgentScenarioCategory;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideDecision;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayClient;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayErrorCode;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayException;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayRequest;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayResponse;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideMode;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideProperties;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent decide 的 <b>只读影子观察</b>服务（W4 冻结契约 §3）。
 *
 * <p><b>边界</b>：本服务只读取本轮已提交的 {@link AgentRunView}/{@link ModelTurnResult}/
 * 工具定义/步骤，构造有界快照并异步调用 Python worker；Python 的返回<b>只用于指标</b>：
 * 不执行工具、不写任何业务表、不发 SSE、不修改 {@code turn}/{@code steps}/{@code exposed}，
 * 也绝不经由 {@code AgentToolCallExecutor}。任何异常都在本服务内吞掉，调用方行为不变。</p>
 *
 * <p><b>默认零成本</b>：{@code decide.mode=java}（默认）时在方法入口直接返回，
 * 不构造快照、不取信号量、不记录任何指标，与未实现时完全一致。</p>
 *
 * <p><b>触发矩阵</b>（全部满足才发起）：mode=shadow；{@code run.depth()==0}；
 * scenario 为 {@code PROJECT_RESEARCH} 且不允许写工具；本轮 {@code toolCalls} 非空且每个
 * 调用都能在本次 {@code exposed} 中找到且 {@code writesBusinessData()==false}；
 * 快照内仍能容纳全部被调用的工具；信号量可用。</p>
 *
 * <p><b>日志红线</b>：只记录失败原因与耗时；不记录 goal、快照内容、工具参数、响应正文或令牌。</p>
 */
@Service
public class AgentDecideShadowService {

    private static final Logger log = LoggerFactory.getLogger(AgentDecideShadowService.class);

    static final String METRIC_CALLS = "decide.shadow.calls";
    static final String METRIC_AGREEMENT = "decide.shadow.agreement";
    static final String METRIC_INVALID_TOOL_CALL = "decide.shadow.invalid_tool_call";
    static final String METRIC_CONTRACT = "decide.shadow.contract";
    static final String METRIC_FAILURE = "decide.shadow.failure";
    static final String METRIC_SKIPPED = "decide.shadow.skipped";
    static final String METRIC_TRUNCATED = "decide.shadow.truncated";
    static final String METRIC_LATENCY = "decide.shadow.latency";

    static final String SHADOW_SCENARIO_CODE = "PROJECT_RESEARCH";
    static final String LOCALE = "zh-CN";
    static final int MAX_GOAL_CHARS = 2000;
    static final int MAX_FACTS = 8;
    static final int MAX_FACT_CHARS = 500;
    static final int MAX_OBSERVATIONS = 6;
    static final int MAX_OBSERVATION_CHARS = 400;
    static final int MAX_TOOL_DESCRIPTION_CHARS = 300;
    static final int MAX_TOOL_SCHEMA_CHARS = 2000;
    static final int MAX_INPUT_TOKENS = 24000;
    static final int MAX_OUTPUT_TOKENS = 1000;
    static final int MAX_ATTEMPT = 50;
    static final int MAX_TURN_NO = 500;

    private static final String SNAPSHOT_HASH_PREFIX = "sha256:";

    private final DecideProperties properties;
    private final DecideGatewayClient gatewayClient;
    private final ObjectMapper json;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;

    public AgentDecideShadowService(DecideProperties properties, DecideGatewayClient gatewayClient,
            ObjectMapper json, MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.gatewayClient = Objects.requireNonNull(gatewayClient, "gatewayClient");
        this.json = Objects.requireNonNull(json, "json");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.permits = new Semaphore(properties.maxConcurrency());
    }

    /**
     * 观察一个已经提交到持久化的模型轮次（工具轮边界）。
     *
     * <p>本方法是<b>尽力而为</b>的：不抛异常、不阻塞等待 worker、不改动任何入参；
     * 是否真正发起由 {@code decide.mode}、场景、轮次形态与并发额度共同决定。</p>
     */
    public void observe(AgentRunView run, AgentScenario scenario, ModelTurnResult turn,
            List<AgentToolDefinition> exposed, List<AgentStepView> steps) {
        try {
            // 默认 java：零外呼、零快照、零指标（连 skipped{disabled} 也不记）。
            if (properties.mode() != DecideMode.SHADOW) {
                return;
            }
            String ineligible = ineligibleReason(run, scenario, turn, exposed);
            if (ineligible != null) {
                recordSkipped(ineligible);
                return;
            }

            Snapshot snapshot = buildSnapshot(run, scenario, turn, exposed, steps);
            if (snapshot == null) {
                // 快照无法容纳全部被调用工具（schema 超界或 maxTools 截断）：该轮不可比较。
                recordSkipped("turn");
                return;
            }
            if (snapshot.truncated()) {
                recordTruncated();
            }

            DecideDecision javaDecision = javaDecision(turn);
            String javaToolName = javaToolName(turn);
            if (!permits.tryAcquire()) {
                recordSkipped("saturated");
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        runShadow(snapshot.request(), javaDecision, javaToolName);
                    } catch (RuntimeException ignored) {
                        // runShadow 已把可分类失败记成指标；这里只防信号量泄漏。
                    } finally {
                        permits.release();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                permits.release();
                recordSkipped("saturated");
            }
        } catch (RuntimeException ignored) {
            // 影子观察绝不能影响业务调用方。
            log.debug("decide shadow 观察失败（已忽略）: type={}", ignored.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 触发条件（§3.1）：返回 null 表示可以发起，否则返回 skipped 的 reason。 */
    private String ineligibleReason(AgentRunView run, AgentScenario scenario, ModelTurnResult turn,
            List<AgentToolDefinition> exposed) {
        if (run == null || scenario == null || run.depth() != 0
                || !SHADOW_SCENARIO_CODE.equals(scenario.code())
                || scenario.allowWriteTools()) {
            return "scenario";
        }
        if (turn == null || turn.toolCalls().isEmpty()) {
            return "turn";
        }
        Map<String, AgentToolDefinition> byName = new LinkedHashMap<>();
        if (exposed != null) {
            for (AgentToolDefinition definition : exposed) {
                if (definition != null) {
                    byName.putIfAbsent(definition.name(), definition);
                }
            }
        }
        for (ModelToolCall call : turn.toolCalls()) {
            AgentToolDefinition definition = byName.get(call.name());
            if (definition == null || definition.writesBusinessData()) {
                return "turn";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 快照构造（§3.2）
    // ------------------------------------------------------------------

    private Snapshot buildSnapshot(AgentRunView run, AgentScenario scenario, ModelTurnResult turn,
            List<AgentToolDefinition> exposed, List<AgentStepView> steps) {
        String runId = run.id() == null ? "" : run.id().toString();
        int attempt = clamp(run.retryCount() + 1, 1, MAX_ATTEMPT);
        int turnNo = clamp(run.stepsUsed() + 1, 0, MAX_TURN_NO);
        String scenarioCode = scenario.code();
        AgentScenarioCategory category = scenario.category();
        String scenarioCategory = category == null
                ? AgentScenarioCategory.SCENARIO.name() : category.name();
        String goal = goal(run, scenario);

        List<String> facts = new ArrayList<>(facts(steps));
        List<DecideGatewayRequest.Observation> observations = new ArrayList<>(observations(steps));
        List<DecideGatewayRequest.Tool> tools = new ArrayList<>(tools(exposed));

        // 本次真实调用的工具必须仍在快照的 tools 内，否则 Python 不可能返回合法 CALL_TOOL。
        Set<String> calledTools = turn.toolCalls().stream().map(ModelToolCall::name)
                .collect(Collectors.toSet());
        Set<String> sentTools = tools.stream().map(DecideGatewayRequest.Tool::name)
                .collect(Collectors.toSet());
        if (!sentTools.containsAll(calledTools)) {
            return null;
        }

        DecideGatewayRequest request = request(runId, attempt, turnNo, placeholderHash(), scenarioCode,
                scenarioCategory, goal, facts, observations, tools);
        boolean truncated = false;
        while (DecideSnapshots.canonicalJson(json.valueToTree(request)).length()
                > properties.snapshotMaxChars()) {
            if (!observations.isEmpty()) {
                // 先裁最旧的观察，再裁事实，最后裁工具；每裁一步都重新计算快照长度。
                observations.remove(0);
            } else if (!facts.isEmpty()) {
                facts.remove(0);
            } else if (tools.size() > 1) {
                tools.remove(tools.size() - 1);
            } else if (goal.length() > 1) {
                goal = bound(goal, Math.max(1, goal.length() / 2));
            } else {
                break;
            }
            truncated = true;
            request = request(runId, attempt, turnNo, placeholderHash(), scenarioCode,
                    scenarioCategory, goal, facts, observations, tools);
        }
        return new Snapshot(request.withSnapshotHash(snapshotHash(request)), truncated);
    }

    private DecideGatewayRequest request(String runId, int attempt, int turnNo, String snapshotHash,
            String scenarioCode, String scenarioCategory, String goal, List<String> facts,
            List<DecideGatewayRequest.Observation> observations, List<DecideGatewayRequest.Tool> tools) {
        return new DecideGatewayRequest(
                1,
                runId,
                attempt,
                turnNo,
                snapshotHash,
                new DecideGatewayRequest.Scenario(scenarioCode, scenarioCategory),
                goal,
                LOCALE,
                new DecideGatewayRequest.Context(facts, List.of(), observations),
                tools,
                DecideGatewayRequest.Policy.READ_ONLY,
                new DecideGatewayRequest.Budget(MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS,
                        properties.deadlineMs()));
    }

    /** 快照哈希：canonicalJson(快照去掉 snapshotHash/schemaVersion)。 */
    private String snapshotHash(DecideGatewayRequest request) {
        com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(request);
        node.remove("snapshotHash");
        node.remove("schemaVersion");
        return SNAPSHOT_HASH_PREFIX
                + DecideSnapshots.sha256Hex(DecideSnapshots.canonicalJson(node)).substring(0, 32);
    }

    private static String placeholderHash() {
        return SNAPSHOT_HASH_PREFIX + "0".repeat(32);
    }

    private static String goal(AgentRunView run, AgentScenario scenario) {
        String goal = run.goal();
        if (goal == null || goal.isBlank()) {
            goal = scenario.displayName();
        }
        if (goal == null || goal.isBlank()) {
            goal = SHADOW_SCENARIO_CODE;
        }
        return bound(goal.strip(), MAX_GOAL_CHARS);
    }

    /**
     * 事实层：工具结果中<b>已结构化的事实</b>（{@code data.taskFacts}/{@code data.facts}）
     * 与已完成委派的产出摘要；每条 ≤500，最多保留最近 8 条。
     */
    private List<String> facts(List<AgentStepView> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<String> facts = new ArrayList<>();
        for (AgentStepView step : steps) {
            if (step == null || step.output() == null) {
                continue;
            }
            if (step.type() == AgentStepType.TOOL_CALL_COMPLETED
                    && "TOOL_SUCCESS".equals(step.reason())) {
                JsonNode data = step.output().get("data");
                collectFactItems(data == null ? null : data.get("taskFacts"), facts);
                collectFactItems(data == null ? null : data.get("facts"), facts);
            } else if (step.type() == AgentStepType.DELEGATION_COMPLETED) {
                addFact(facts, bound(compact(step.output()), MAX_FACT_CHARS));
            }
        }
        if (facts.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, facts.size() - MAX_FACTS);
        return List.copyOf(facts.subList(from, facts.size()));
    }

    private void collectFactItems(JsonNode items, List<String> facts) {
        if (items == null || items.isNull()) {
            return;
        }
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (item.isTextual()) {
                    addFact(facts, bound(item.asText(), MAX_FACT_CHARS));
                } else if (item.isObject()) {
                    addFact(facts, bound(compact(item), MAX_FACT_CHARS));
                }
            }
            return;
        }
        if (items.isTextual()) {
            addFact(facts, bound(items.asText(), MAX_FACT_CHARS));
        }
    }

    private static void addFact(List<String> facts, String fact) {
        if (fact != null && !fact.isBlank()) {
            facts.add(fact);
        }
    }

    /** 观察层：最近 6 个工具结果的工具名 + 有界摘要（≤400），不含结果全文。 */
    private List<DecideGatewayRequest.Observation> observations(List<AgentStepView> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<DecideGatewayRequest.Observation> observations = new ArrayList<>();
        for (AgentStepView step : steps) {
            if (step == null || step.type() != AgentStepType.TOOL_CALL_COMPLETED
                    || step.toolName() == null || step.toolName().isBlank()) {
                continue;
            }
            observations.add(new DecideGatewayRequest.Observation(step.toolName(),
                    bound(summary(step.output()), MAX_OBSERVATION_CHARS)));
        }
        if (observations.size() > MAX_OBSERVATIONS) {
            observations = new ArrayList<>(observations.subList(
                    observations.size() - MAX_OBSERVATIONS, observations.size()));
        }
        return observations;
    }

    private String summary(JsonNode output) {
        if (output == null || output.isNull()) {
            return "";
        }
        if (output.isTextual()) {
            return output.asText();
        }
        if (output.isObject()) {
            String direct = firstText(output, List.of("summary", "message"));
            if (direct != null) {
                return direct;
            }
            JsonNode data = output.get("data");
            if (data != null && data.isObject()) {
                String nested = firstText(data, List.of("summary", "answer", "content", "message"));
                if (nested != null) {
                    return nested;
                }
            }
        }
        return compact(output);
    }

    private static String firstText(JsonNode object, List<String> fields) {
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /** 工具层：本次 exposed 中的只读工具，schema 压缩 ≤2000，按 name 排序，≤maxTools。 */
    private List<DecideGatewayRequest.Tool> tools(List<AgentToolDefinition> exposed) {
        if (exposed == null || exposed.isEmpty()) {
            return List.of();
        }
        Map<String, DecideGatewayRequest.Tool> byName = new TreeMap<>();
        for (AgentToolDefinition definition : exposed) {
            if (definition == null || definition.writesBusinessData()) {
                continue;
            }
            String schema = compact(definition.inputSchema());
            if (schema.length() > MAX_TOOL_SCHEMA_CHARS) {
                continue;
            }
            byName.putIfAbsent(definition.name(), new DecideGatewayRequest.Tool(
                    definition.name(),
                    bound(definition.description(), MAX_TOOL_DESCRIPTION_CHARS),
                    definition.inputSchema().deepCopy(),
                    "READ_ONLY"));
        }
        return byName.values().stream().limit(properties.maxTools()).toList();
    }

    // ------------------------------------------------------------------
    // 执行与指标（§3.3/§3.4）
    // ------------------------------------------------------------------

    private void runShadow(DecideGatewayRequest request, DecideDecision javaDecision,
            String javaToolName) {
        long startNanos = System.nanoTime();
        String outcome = "failure";
        try {
            DecideGatewayResponse response = gatewayClient.decide(request);
            recordContract("valid");
            recordCall(response.decision());
            String pythonToolName = response.toolCall() == null ? null : response.toolCall().name();
            if (response.decision() == DecideDecision.CALL_TOOL
                    && !requestToolNames(request).contains(pythonToolName)) {
                // 防御性检查：生产客户端已在下发 tools 内做过成员校验，这里仍按 §3.4 计数。
                recordInvalidToolCall();
            }
            boolean sameType = javaDecision == response.decision();
            boolean sameTool = javaDecision != DecideDecision.CALL_TOOL
                    || Objects.equals(javaToolName, pythonToolName);
            recordAgreement(sameType && sameTool);
            outcome = "success";
        } catch (DecideGatewayException failure) {
            recordContract("invalid");
            recordFailure(failure.reason());
            log.warn("decide shadow 失败丢弃: reason={} elapsedMs={}",
                    failure.reason().tag(), elapsedMillis(startNanos));
        } catch (RuntimeException unexpected) {
            recordContract("invalid");
            recordFailure(DecideGatewayErrorCode.UNEXPECTED);
            log.warn("decide shadow 未预期异常: type={} elapsedMs={}",
                    unexpected.getClass().getSimpleName(), elapsedMillis(startNanos));
        } finally {
            recordLatency(outcome, System.nanoTime() - startNanos);
        }
    }

    private static DecideDecision javaDecision(ModelTurnResult turn) {
        if (turn != null && !turn.toolCalls().isEmpty()) {
            return DecideDecision.CALL_TOOL;
        }
        if (turn != null && !turn.content().isBlank()) {
            return DecideDecision.FINAL;
        }
        return DecideDecision.NO_OP;
    }

    private static String javaToolName(ModelTurnResult turn) {
        if (turn == null || turn.toolCalls().isEmpty()) {
            return null;
        }
        return turn.toolCalls().getFirst().name();
    }

    private static Set<String> requestToolNames(DecideGatewayRequest request) {
        return request.tools().stream().map(DecideGatewayRequest.Tool::name)
                .collect(Collectors.toSet());
    }

    private void recordCall(DecideDecision decision) {
        counter(METRIC_CALLS, "decision", decision.name());
    }

    private void recordAgreement(boolean match) {
        counter(METRIC_AGREEMENT, "result", match ? "match" : "mismatch");
    }

    private void recordInvalidToolCall() {
        counter(METRIC_INVALID_TOOL_CALL);
    }

    private void recordContract(String result) {
        counter(METRIC_CONTRACT, "result", result);
    }

    private void recordFailure(DecideGatewayErrorCode reason) {
        counter(METRIC_FAILURE, "reason", reason.tag());
    }

    private void recordSkipped(String reason) {
        counter(METRIC_SKIPPED, "reason", reason);
    }

    private void recordTruncated() {
        counter(METRIC_TRUNCATED);
    }

    private void recordLatency(String outcome, long nanos) {
        try {
            meterRegistry.timer(METRIC_LATENCY, "outcome", outcome)
                    .record(nanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // 指标失败不影响业务。
        }
    }

    private void counter(String name, String... tags) {
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException ignored) {
            // 指标失败不影响业务。
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private String compact(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException fallback) {
            return node.toString();
        }
    }

    static String bound(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 已构造好、可直接发送的快照（含是否发生截断）。 */
    private record Snapshot(DecideGatewayRequest request, boolean truncated) {
    }
}
