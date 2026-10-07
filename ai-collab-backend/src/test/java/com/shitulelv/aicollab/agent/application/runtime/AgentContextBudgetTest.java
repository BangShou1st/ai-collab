package com.shitulelv.aicollab.agent.application.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 单次请求输入预算：窗口匹配、约束绑定与估算标记。 */
class AgentContextBudgetTest {

    @Test
    void windowOverrideMatchesTypedKeyFirst() {
        var window = AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "gpt-4o",
                Map.of("OPENAI_COMPATIBLE:gpt-4o", 128_000, "gpt-4o", 8_000));
        assertThat(window.windowTokens()).isEqualTo(128_000);
        assertThat(window.estimated()).isFalse();
        assertThat(window.basis()).isEqualTo("OPENAI_COMPATIBLE:gpt-4o");
    }

    @Test
    void windowOverrideFallsBackToPlainModelKey() {
        var window = AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "gpt-4o", Map.of("gpt-4o", 8_000));
        assertThat(window.windowTokens()).isEqualTo(8_000);
        assertThat(window.estimated()).isFalse();
    }

    @Test
    void unknownWindowIsMarkedEstimated() {
        assertThat(AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "unknown-model", Map.of("gpt-4o", 8_000)))
                .extracting(AgentContextBudget.ModelWindow::windowTokens, AgentContextBudget.ModelWindow::estimated)
                .containsExactly((Object) null, true);
        assertThat(AgentContextBudget.resolveWindow(null, null, Map.of("gpt-4o", 8_000)).estimated()).isTrue();
    }

    @Test
    void unknownWindowKeepsConservativeApplicationCapOnly() {
        var properties = AgentContextProperties.defaults();
        var budget = AgentContextBudget.perRequest(properties, AgentContextBudget.ModelWindow.unknown(), 30_000);
        assertThat(budget.availableInputTokens()).isEqualTo(30_000);
        assertThat(budget.binding()).isEqualTo(AgentContextBudget.BINDING_RUN_INPUT_BUDGET);
        assertThat(budget.windowEstimated()).isTrue();
    }
    @Test
    void perRequestCapBindsWhenRemainingBudgetIsLarger() {
        var properties = AgentContextProperties.defaults();
        var budget = AgentContextBudget.perRequest(properties, AgentContextBudget.ModelWindow.unknown(), 80_000);
        assertThat(budget.availableInputTokens()).isEqualTo(50_000);
        assertThat(budget.binding()).isEqualTo(AgentContextBudget.BINDING_PER_REQUEST_CAP);
    }

    @Test
    void knownModelWindowTightensBudgetWithReserveAndMargin() {
        var properties = new AgentContextProperties(true, 50_000, 8_000, 2_000,
                Map.of("test-model", 20_000));
        var window = AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "test-model", properties.windowOverrides());
        var budget = AgentContextBudget.perRequest(properties, window, 50_000);
        assertThat(budget.availableInputTokens()).isEqualTo(20_000 - 8_000 - 2_000);
        assertThat(budget.binding()).isEqualTo(AgentContextBudget.BINDING_MODEL_WINDOW);
        assertThat(budget.windowEstimated()).isFalse();
    }

    @Test
    void exhaustedRunBudgetYieldsZeroWithoutNegative() {
        var properties = AgentContextProperties.defaults();
        var budget = AgentContextBudget.perRequest(properties, AgentContextBudget.ModelWindow.unknown(), -5);
        assertThat(budget.availableInputTokens()).isZero();
        assertThat(budget.binding()).isEqualTo(AgentContextBudget.BINDING_RUN_INPUT_BUDGET);
    }

    // ==================================================================
    // 256k 软压缩触发线（设计 2 节）：T 是整理旧轨迹的策略线，不是请求硬上限
    // ==================================================================

    @Test
    void confirmedLargeWindowTriggersSoftCompactionAt256k() {
        // 已确认 1M 窗口：H = 1M - 输出预留 - 安全余量，T 取 min(256k, 0.85H) = 256k
        var properties = new AgentContextProperties(true, 50_000, 8_000, 2_000,
                Map.of("big-model", 1_000_000));
        var window = AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "big-model", properties.windowOverrides());
        var budget = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 8_000, false);

        assertThat(budget.softTriggerTokens()).isEqualTo(256_000);
        // 硬边界仍接近模型真实容量，而不是被 256k 绑定
        assertThat(budget.hardInputTokens()).isEqualTo(1_000_000 - 8_000 - 2_000);
        assertThat(budget.hardInputTokens()).isGreaterThan(256_000);
        // 压缩后软目标是 T 的一半（约 128k），不是硬约束
        assertThat(budget.compactionTargetTokens()).isEqualTo(128_000);
        assertThat(budget.windowEstimated()).isFalse();
    }

    @Test
    void largeWindowRequestIsNotCappedAt256kOr50k() {
        // 新策略的核心：请求可用输入取 H（模型安全可用输入），
        // 不再被 256k 或应用单次 50k 上限绑定
        var properties = new AgentContextProperties(true, 50_000, 8_000, 2_000,
                Map.of("big-model", 1_000_000));
        var window = AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "big-model", properties.windowOverrides());
        var budget = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 8_000, false);

        assertThat(budget.availableInputTokens()).isEqualTo(990_000);
        assertThat(budget.availableInputTokens()).isGreaterThan(256_000);
    }

    @Test
    void smallWindowTriggersCompactionEarlierThan256k() {
        // 较小窗口模型自动得到较小的 H/T，不把 128k 模型强行按 256k 发送
        var properties = new AgentContextProperties(true, 50_000, 8_000, 2_000,
                Map.of("small-model", 128_000));
        var window = AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "small-model", properties.windowOverrides());
        var budget = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 8_000, false);

        int expectedHard = 128_000 - 8_000 - 2_000;
        assertThat(budget.hardInputTokens()).isEqualTo(expectedHard);
        // T = floor(H * 0.85) 提前于 256k 触发
        assertThat(budget.softTriggerTokens()).isEqualTo((int) Math.floor(expectedHard * 0.85));
        assertThat(budget.softTriggerTokens()).isLessThan(256_000);
        // 请求可用输入等于 H：小窗口模型仍能发满自己的窗口
        assertThat(budget.availableInputTokens()).isEqualTo(expectedHard);
    }

    @Test
    void softTriggerReportsCompactionWithoutRejectingTheRequest() {
        // 一个工具批次让活跃上下文从 250k 跳到 275k：这是触发压缩，不是协议违规
        var properties = new AgentContextProperties(true, 50_000, 8_000, 2_000,
                Map.of("big-model", 1_000_000));
        var window = AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "big-model", properties.windowOverrides());
        var budget = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 8_000, false);

        assertThat(budget.shouldCompact(250_000)).isFalse();
        assertThat(budget.shouldCompact(275_000)).isTrue();
        // 超过 T 不等于不可继续：H 内仍有充足空间
        assertThat(budget.availableInputTokens()).isGreaterThan(275_000);
    }

    @Test
    void unknownWindowKeepsConservativeFallbackAndMarksEstimated() {
        // 窗口未知时保留 50k 兼容回退并显式标记估算（不是对任意未知小模型的安全保证）
        var properties = AgentContextProperties.defaults();
        var budget = AgentContextBudget.perRequest(properties, AgentContextBudget.ModelWindow.unknown(),
                Integer.MAX_VALUE, 8_000, false);

        assertThat(budget.windowEstimated()).isTrue();
        assertThat(budget.hardInputTokens()).isEqualTo(AgentContextBudget.UNKNOWN_WINDOW_FALLBACK_TOKENS);
        assertThat(budget.availableInputTokens())
                .isEqualTo(AgentContextBudget.UNKNOWN_WINDOW_FALLBACK_TOKENS);
    }

    @Test
    void outputReserveUsesTheActualOutboundValue() {
        // 输出预留必须与本次实际出站值一致，不能用与实际发送不同的数字计算窗口
        var properties = new AgentContextProperties(true, 50_000, 8_000, 2_000,
                Map.of("big-model", 1_000_000));
        var window = AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "big-model", properties.windowOverrides());
        var withSmallOutput = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 4_000, false);
        var withLargeOutput = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 32_000, false);

        assertThat(withSmallOutput.hardInputTokens()).isEqualTo(1_000_000 - 4_000 - 2_000);
        assertThat(withLargeOutput.hardInputTokens()).isEqualTo(1_000_000 - 32_000 - 2_000);
        assertThat(withSmallOutput.hardInputTokens()).isGreaterThan(withLargeOutput.hardInputTokens());
    }

    @Test
    void v1StillAppliesPerRequestCapAndRunRemaining() {
        // v1 兼容路径：应用单次上限与运行剩余额度照旧收紧（旧运行不被重解释）
        var properties = new AgentContextProperties(true, 50_000, 8_000, 2_000,
                Map.of("big-model", 1_000_000));
        var window = AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "big-model", properties.windowOverrides());
        var capped = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 8_000, true);
        assertThat(capped.availableInputTokens()).isEqualTo(50_000);
        assertThat(capped.binding()).isEqualTo(AgentContextBudget.BINDING_PER_REQUEST_CAP);

        var runLimited = AgentContextBudget.perRequest(properties, window, 30_000, 8_000, true);
        assertThat(runLimited.availableInputTokens()).isEqualTo(30_000);
        assertThat(runLimited.binding()).isEqualTo(AgentContextBudget.BINDING_RUN_INPUT_BUDGET);
    }
}
