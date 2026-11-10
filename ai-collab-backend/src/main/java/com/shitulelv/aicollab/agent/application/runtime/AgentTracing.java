package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Agent 链路自定义 span 薄封装（O2，设计 docs/design/observability-and-mq-migration.md §2.2）。
 *
 * <p>只加观测、不改任何业务逻辑：三个核心观测面——模型轮出站、工具执行、委派受理——
 * 以及规划生成管线各提供一个包装方法。所有方法在 {@link Tracer} 缺失（测试便捷装配、
 * tracing 未启用）时直接透传调用，span 创建/异常记录/结束全程不抛出，观测失败不影响业务结果。</p>
 *
 * <p>注入方式遵循项目既有的最小侵入模式（参考 {@code RoutingAgentModelExecutor} 对
 * {@code AgentRuntimeMetrics} 的可选注入）：Bean 经 {@code ObjectProvider} 延迟解析创建
 * （无 Tracer 时得到 no-op 实例），业务类以可选依赖接收，避免改动既有测试构造器。</p>
 */
public class AgentTracing {

    /** 可为 null（tracing 未装配时的 no-op 形态）：观测缺失不影响业务。 */
    private final Tracer tracer;

    public AgentTracing(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * 模型轮出站 span（{@code agent.model.turn}）。
     * tag：agent.runId、agent.step（当前已用步数 + 1，即本轮次序）。
     */
    public <T> T modelTurn(AgentRunView run, Supplier<T> call) {
        return inSpan("agent.model.turn",
                () -> new String[] {
                        "agent.runId", run.id().toString(),
                        "agent.step", Integer.toString(run.stepsUsed() + 1)},
                call);
    }

    /**
     * 工具执行 span（{@code agent.tool.execute}）。
     * tag：agent.runId、agent.toolName。
     */
    public <T> T toolExecution(AgentRunView run, String toolName, Supplier<T> call) {
        return inSpan("agent.tool.execute",
                () -> new String[] {
                        "agent.runId", run.id().toString(),
                        "agent.toolName", toolName == null ? "unknown" : toolName},
                call);
    }

    /**
     * 委派受理 span（{@code agent.delegation.admit}）。
     * tag：agent.parentRunId（发起委派的父运行）、agent.objectiveHash（目标文本的
     * SHA-256 前 12 位十六进制——避免把用户目标明文写进 span tag 随 trace 外泄）。
     */
    public <T> T delegationAccept(AgentRunView run, String objective, Supplier<T> call) {
        return inSpan("agent.delegation.admit",
                () -> new String[] {
                        "agent.parentRunId", run.id().toString(),
                        "agent.objectiveHash", objectiveHashPrefix(objective)},
                call);
    }

    /**
     * 规划生成 span（{@code planning.generate}）。
     * tag：plan.id、plan.stage（SKELETON / DETAIL）。
     */
    public <T> T planningGeneration(UUID planId, String stage, Supplier<T> call) {
        return inSpan("planning.generate",
                () -> new String[] {
                        "plan.id", planId == null ? "unknown" : planId.toString(),
                        "plan.stage", stage == null ? "unknown" : stage},
                call);
    }

    /** {@link #planningGeneration} 的无返回值形态。 */
    public void planningGeneration(UUID planId, String stage, Runnable call) {
        planningGeneration(planId, stage, () -> {
            call.run();
            return null;
        });
    }

    private <T> T inSpan(String name, Supplier<String[]> tags, Supplier<T> call) {
        if (tracer == null) {
            return call.get();
        }
        // micrometer-tracing 1.7 API：Builder.start() 启动 span，Tracer.withSpan(...) 挂载当前上下文
        Span span = tracer.spanBuilder().name(name).start();
        try {
            String[] kv = tags.get();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                span.tag(kv[i], kv[i + 1]);
            }
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                return call.get();
            } catch (RuntimeException failure) {
                span.error(failure);
                throw failure;
            }
        } finally {
            span.end();
        }
    }

    /** 目标文本的 SHA-256 前 12 位十六进制前缀；null/异常时返回 "unknown"。 */
    static String objectiveHashPrefix(String objective) {
        if (objective == null || objective.isBlank()) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(objective.getBytes(StandardCharsets.UTF_8)), 0, 6);
        } catch (NoSuchAlgorithmException unreachable) {
            return "unknown";
        }
    }
}
