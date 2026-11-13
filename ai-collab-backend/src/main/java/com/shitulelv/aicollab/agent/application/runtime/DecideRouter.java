package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.ToolArgumentValidator;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideDecision;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayClient;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayException;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayRequest;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideGatewayResponse;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideMode;
import com.shitulelv.aicollab.agent.infrastructure.decide.DecideProperties;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * decide Python 主链路由器。
 *
 * <p>本类只负责“是否采用 Python 的单轮建议”以及把它转换成 Java 已经认识的
 * {@link ModelTurnResult}；它不执行工具、不写业务表、不创建审批。合法的 CALL_TOOL
 * 仍由 {@link AgentToolCallExecutor} 走完整的工具暴露集合、Schema、权限、预算、审批、
 * 租约和幂等校验。</p>
 *
 * <p>路由边界刻意保守：只有根运行、{@code PROJECT_RESEARCH}、不允许写工具且存在只读
 * 工具时才调用 Python。FINAL/ASK_USER/NO_OP 没有可恢复的文本正文，回退 Java 模型；
 * 超时、非法 JSON、未知工具、Schema 不符、取消竞态也回退 Java，并留下稳定的
 * {@code python_fallback:<reason>} 标记与低基数指标。</p>
 */
@Service
public class DecideRouter {

    private static final Logger log = LoggerFactory.getLogger(DecideRouter.class);

    static final String PROVIDER = "PYTHON_DECIDE";
    static final String MODEL = "ai-worker";
    static final String METRIC_CALLS = "decide.router.calls";
    static final String METRIC_FALLBACK = "decide.router.fallback";
    static final String METRIC_HANDLED = "decide.router.handled";
    static final String METRIC_INVALID_TOOL_CALL = "decide.router.invalid_tool_call";
    static final String METRIC_LATENCY = "decide.router.latency";
    static final String SCENARIO_CODE = "PROJECT_RESEARCH";
    private static final int MAX_INPUT_TOKENS = 24000;

    private final DecideProperties properties;
    private final DecideGatewayClient gateway;
    private final AgentDecideShadowService snapshots;
    private final MeterRegistry meterRegistry;

    public DecideRouter(
            DecideProperties properties,
            DecideGatewayClient gateway,
            AgentDecideShadowService snapshots,
            MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /**
     * 主链开关的只读判据：coordinator 用它在默认 java/shadow 下完全跳过 router，
     * 保持既有调用链的零额外 DB/外呼成本。
     */
    public boolean enabledForMainChain() {
        return properties.mode() == DecideMode.PYTHON;
    }

    /**
     * 在 Java 模型调用前尝试一次 Python 决策。
     *
     * @param availableInputTokens 当前请求可用的输入预算
     * @param remainingMillis      当前运行剩余时长
     * @param modelCallId          已持久化的模型调用身份，用于生成稳定的工具调用 id
     */
    public Route route(
            AgentRunView run,
            AgentScenario scenario,
            List<AgentToolDefinition> exposed,
            List<AgentStepView> steps,
            int availableInputTokens,
            long remainingMillis,
            boolean finalizing,
            String modelCallId) {
        // 默认 java/shadow 绝不进入主链，也不产生 router 指标或快照。
        if (properties.mode() != DecideMode.PYTHON) {
            return Route.fallback(null, null);
        }
        if (run == null || scenario == null || run.depth() != 0
                || !SCENARIO_CODE.equals(scenario.code()) || scenario.allowWriteTools()
                || finalizing) {
            return fallback("scenario");
        }
        if (exposed == null || exposed.stream().noneMatch(tool -> !tool.writesBusinessData())) {
            return fallback("no_tools");
        }
        if (remainingMillis <= 0) {
            return fallback("deadline");
        }
        if (availableInputTokens <= 0) {
            return fallback("budget");
        }
        if (modelCallId == null || modelCallId.isBlank()) {
            return fallback("model_call_id");
        }

        int deadlineMs = (int) Math.max(1, Math.min(properties.deadlineMs(), remainingMillis));
        int inputBudget = Math.max(1, Math.min(MAX_INPUT_TOKENS, availableInputTokens));
        DecideGatewayRequest request = snapshots.routingRequest(
                run, scenario, exposed, steps, deadlineMs, inputBudget);
        if (request == null) {
            return fallback("snapshot");
        }

        long started = System.nanoTime();
        try {
            DecideGatewayResponse response = gateway.decide(request);
            recordCounter(METRIC_CALLS, "decision", response.decision().name());
            if (response.decision() != DecideDecision.CALL_TOOL) {
                recordLatency("success", System.nanoTime() - started);
                return fallback("decision_" + response.decision().name().toLowerCase());
            }
            AgentToolDefinition definition = exposed.stream()
                    .filter(tool -> Objects.equals(tool.name(), response.toolCall().name()))
                    .findFirst().orElse(null);
            if (definition == null) {
                recordCounter(METRIC_INVALID_TOOL_CALL);
                recordLatency("success", System.nanoTime() - started);
                return fallback("unknown_tool");
            }
            if (definition.writesBusinessData()) {
                recordCounter(METRIC_INVALID_TOOL_CALL);
                recordLatency("success", System.nanoTime() - started);
                return fallback("write_tool");
            }
            String schemaError = ToolArgumentValidator.validate(
                    response.toolCall().arguments(), definition.inputSchema());
            if (schemaError != null) {
                recordCounter(METRIC_INVALID_TOOL_CALL);
                recordLatency("success", System.nanoTime() - started);
                return fallback("schema");
            }
            long latency = response.latencyMs() == null
                    ? elapsedMillis(started) : Math.max(0, response.latencyMs());
            ModelUsage usage = usage(response);
            ModelToolCall call = new ModelToolCall(
                    "python-decide-" + modelCallId,
                    response.toolCall().name(),
                    response.toolCall().arguments());
            ModelTurnResult turn = new ModelTurnResult("", List.of(call),
                    ModelFinishReason.TOOL_CALLS, usage, PROVIDER,
                    response.modelAlias() == null || response.modelAlias().isBlank()
                            ? MODEL : response.modelAlias(),
                    latency);
            recordCounter(METRIC_HANDLED);
            recordLatency("success", System.nanoTime() - started);
            return Route.handled(turn, "python:CALL_TOOL", response.decision().name(),
                    response.toolCall().name(), response.promptVersion(), response.modelAlias());
        } catch (DecideGatewayException failure) {
            recordLatency("failure", System.nanoTime() - started);
            log.warn("decide 主链回退: reason={} elapsedMs={}",
                    failure.reason().tag(), elapsedMillis(started));
            return fallback("worker_" + failure.reason().tag());
        } catch (RuntimeException failure) {
            recordLatency("failure", System.nanoTime() - started);
            log.warn("decide 主链未预期异常，回退 Java: type={} elapsedMs={}",
                    failure.getClass().getSimpleName(), elapsedMillis(started));
            return fallback("unexpected");
        }
    }

    private Route fallback(String reason) {
        recordCounter(METRIC_FALLBACK, "reason", reason);
        return Route.fallback("python_fallback:" + reason, reason);
    }

    private static ModelUsage usage(DecideGatewayResponse response) {
        Integer input = response.usage() == null ? null : response.usage().inputTokens();
        Integer output = response.usage() == null ? null : response.usage().outputTokens();
        return new ModelUsage(
                input == null || input < 0 ? null : input,
                output == null || output < 0 ? null : output);
    }

    private void recordCounter(String name, String... tags) {
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException ignored) {
            // 指标失败不影响主链。
        }
    }

    private void recordLatency(String outcome, long nanos) {
        try {
            meterRegistry.timer(METRIC_LATENCY, "outcome", outcome)
                    .record(nanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // 指标失败不影响主链。
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 路由结果。{@code marker} 为 null 表示当前模式不参与主链（java/shadow），
     * 非 null 的 {@code python_fallback:*} 会进入 MODEL_STARTED 事件，便于从运行记录
     * 直接判断本轮为何回退。
     */
    public record Route(
            boolean handled,
            ModelTurnResult turn,
            String marker,
            String decision,
            String toolName,
            String promptVersion,
            String modelAlias) {

        static Route handled(ModelTurnResult turn, String marker, String decision,
                String toolName, String promptVersion, String modelAlias) {
            return new Route(true, turn, marker, decision, toolName, promptVersion, modelAlias);
        }

        static Route fallback(String marker, String reason) {
            return new Route(false, null, marker, reason, null, null, null);
        }
    }
}
