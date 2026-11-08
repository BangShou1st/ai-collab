package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentTracing no-op 形态契约（O2）：Tracer 缺失（测试便捷装配/tracing 未启用）时
 * 观测必须完全透传——调用发生、结果与异常原样进出，观测缺失不影响业务结果。
 */
class AgentTracingTest {

    private static AgentRunView run(int stepsUsed) {
        return new AgentRunView(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                null, "DEVELOPER", 0, "goal", AgentRunStatus.RUNNING,
                10, 20, 3, 1000, 2000, stepsUsed, 0, 0, 0, 0, 0L, 0L,
                false, false, false, 0, null, null, null, "DEFAULT_AGENT",
                1, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void noOpTracerPassesCallsThrough() {
        AgentTracing tracing = new AgentTracing(null);
        AtomicBoolean invoked = new AtomicBoolean(false);

        String modelResult = tracing.modelTurn(run(2), () -> {
            invoked.set(true);
            return "model-ok";
        });
        assertThat(invoked).isTrue();
        assertThat(modelResult).isEqualTo("model-ok");

        invoked.set(false);
        Integer toolResult = tracing.toolExecution(run(0), "search_project_knowledge", () -> {
            invoked.set(true);
            return 7;
        });
        assertThat(invoked).isTrue();
        assertThat(toolResult).isEqualTo(7);

        invoked.set(false);
        String delegationResult = tracing.delegationAccept(run(0), "objective", () -> {
            invoked.set(true);
            return "admitted";
        });
        assertThat(invoked).isTrue();
        assertThat(delegationResult).isEqualTo("admitted");

        invoked.set(false);
        tracing.planningGeneration(UUID.randomUUID(), "SKELETON", () -> invoked.set(true));
        assertThat(invoked).isTrue();
    }

    @Test
    void noOpTracerPropagatesBusinessExceptionsUnchanged() {
        AgentTracing tracing = new AgentTracing(null);
        AtomicBoolean invoked = new AtomicBoolean(false);
        var failure = new IllegalStateException("业务异常原样抛出");
        try {
            tracing.modelTurn(run(0), () -> {
                invoked.set(true);
                throw failure;
            });
        } catch (IllegalStateException caught) {
            assertThat(caught).isSameAs(failure);
        }
        assertThat(invoked).isTrue();
    }

    @Test
    void objectiveHashIsTwelveHexCharsAndNotThePlainText() {
        String hash = AgentTracing.objectiveHashPrefix("检索项目文档中的验收标准并总结覆盖缺口");
        assertThat(hash).hasSize(12).matches("[0-9a-f]{12}");
        assertThat(hash).doesNotContain("检索");
        // 稳定性：同一目标文本得到同一前缀；空白/空值得到 unknown
        assertThat(AgentTracing.objectiveHashPrefix("检索项目文档中的验收标准并总结覆盖缺口")).isEqualTo(hash);
        assertThat(AgentTracing.objectiveHashPrefix(null)).isEqualTo("unknown");
        assertThat(AgentTracing.objectiveHashPrefix("   ")).isEqualTo("unknown");
    }
}
