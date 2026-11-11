package com.shitulelv.aicollab.agent.application.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 单次请求输入预算：窗口匹配、约束绑定、H 派生触发线与保守模式标记。 */
class AgentContextBudgetTest {

    private static AgentContextProperties properties(int windowTokens) {
        return new AgentContextProperties(true, 50_000, 8_000, 2_000,
                Map.of("test-model", windowTokens));
    }

    private static AgentContextBudget.ModelWindow window(AgentContextProperties properties) {
        return AgentContextBudget.resolveWindow("OPENAI_COMPATIBLE", "test-model",
                properties.windowOverrides());
    }

    private static AgentContextBudget.Budget v2Budget(int windowTokens, int maxOutput) {
        var properties = properties(windowTokens);
        return AgentContextBudget.perRequest(properties, window(properties),
                Integer.MAX_VALUE, maxOutput, false);
    }

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
        assertThat(budget.conservativeMode()).isTrue();
    }

    @Test
    void perRequestCapBindsWhenRemainingBudgetIsLarger() {
        var properties = AgentContextProperties.defaults();
        var budget = AgentContextBudget.perRequest(properties, AgentContextBudget.ModelWindow.unknown(), 80_000);
        assertThat(budget.availableInputTokens()).isEqualTo(50_000);
        assertThat(budget.binding()).isEqualTo(AgentContextBudget.BINDING_PER_REQUEST_CAP);
        assertThat(budget.conservativeMode()).isTrue();
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
        assertThat(budget.conservativeMode()).isFalse();
    }

    @Test
    void exhaustedRunBudgetYieldsZeroWithoutNegative() {
        var properties = AgentContextProperties.defaults();
        var budget = AgentContextBudget.perRequest(properties, AgentContextBudget.ModelWindow.unknown(), -5);
        assertThat(budget.availableInputTokens()).isZero();
        assertThat(budget.binding()).isEqualTo(AgentContextBudget.BINDING_RUN_INPUT_BUDGET);
    }

    // ==================================================================
    // v2：T = floor(H * 0.85)，不再有 256k 绝对触发顶；L = floor(T * 0.50)
    // ==================================================================

    @Test
    void oneMillionWindowTriggerIsDerivedFromHWithout256kCap() {
        // H = 1,000,000 - 8,000 - 2,000 = 990,000；T = floor(990,000 * 0.85) = 841,500
        var budget = v2Budget(1_000_000, 8_000);

        assertThat(budget.hardInputTokens()).isEqualTo(990_000);
        assertThat(budget.softTriggerTokens()).isEqualTo(841_500);
        assertThat(budget.compactionTargetTokens()).isEqualTo(420_750);
        assertThat(budget.availableInputTokens()).isEqualTo(990_000);
        assertThat(budget.windowEstimated()).isFalse();
        assertThat(budget.conservativeMode()).isFalse();
    }

    @Test
    void twoHundredThousandWindowTriggerScalesWithH() {
        // H = 190,000；T = 161,500；L = 80,750
        var budget = v2Budget(200_000, 8_000);

        assertThat(budget.hardInputTokens()).isEqualTo(190_000);
        assertThat(budget.softTriggerTokens()).isEqualTo(161_500);
        assertThat(budget.compactionTargetTokens()).isEqualTo(80_750);
        assertThat(budget.availableInputTokens()).isEqualTo(190_000);
    }

    @Test
    void oneHundredTwentyEightThousandWindowTriggerScalesWithH() {
        // H = 118,000；T = 100,300
        var budget = v2Budget(128_000, 8_000);

        assertThat(budget.hardInputTokens()).isEqualTo(118_000);
        assertThat(budget.softTriggerTokens()).isEqualTo(100_300);
        assertThat(budget.availableInputTokens()).isEqualTo(118_000);
    }

    @Test
    void tinyWindowTriggerFollowsHAndTooSmallWindowYieldsZero() {
        // 8k 窗口、本次输出 1k、安全余量 2k：H = 5,000；T = 4,250
        var tiny = v2Budget(8_000, 1_000);
        assertThat(tiny.hardInputTokens()).isEqualTo(5_000);
        assertThat(tiny.softTriggerTokens()).isEqualTo(4_250);
        assertThat(tiny.compactionTargetTokens()).isEqualTo(2_125);

        // 默认输出预留 8k + 安全余量 2k 已吃掉整个 8k 窗口：H 如实为 0，不做负数
        var tooSmall = v2Budget(8_000, 8_000);
        assertThat(tooSmall.hardInputTokens()).isZero();
        assertThat(tooSmall.softTriggerTokens()).isZero();
        assertThat(tooSmall.availableInputTokens()).isZero();
    }

    @Test
    void shouldCompactExactlyAtHBasedTriggerBoundary() {
        var budget = v2Budget(1_000_000, 8_000);

        assertThat(budget.shouldCompact(841_499)).isFalse();
        assertThat(budget.shouldCompact(841_500)).isTrue();
        // 超过 T 只是触发整理，不等于拒绝请求：H 内仍有空间
        assertThat(budget.availableInputTokens()).isGreaterThan(budget.softTriggerTokens());
    }

    @Test
    void unknownWindowUsesConservativeFallbackAndMarksMode() {
        // 未知窗口：H 走 50k 兼容回退，T = floor(50,000 * 0.85) = 42,500
        var properties = AgentContextProperties.defaults();
        var budget = AgentContextBudget.perRequest(properties, AgentContextBudget.ModelWindow.unknown(),
                Integer.MAX_VALUE, 8_000, false);

        assertThat(budget.hardInputTokens()).isEqualTo(50_000);
        assertThat(budget.softTriggerTokens()).isEqualTo(42_500);
        assertThat(budget.compactionTargetTokens()).isEqualTo(21_250);
        assertThat(budget.availableInputTokens()).isEqualTo(50_000);
        assertThat(budget.binding()).isEqualTo(AgentContextBudget.BINDING_PER_REQUEST_CAP);
        assertThat(budget.windowEstimated()).isTrue();
        assertThat(budget.conservativeMode()).isTrue();
    }

    @Test
    void unverifiedWindowUsesItsValueButRemainsMarkedEstimated() {
        // 未验证档案有可用数值：conservativeMode=false，但 windowEstimated 仍为 true
        var properties = AgentContextProperties.defaults();
        var unverified = new AgentContextBudget.ModelWindow(200_000, "PROFILE_UNVERIFIED:42", true);
        var budget = AgentContextBudget.perRequest(properties, unverified, Integer.MAX_VALUE, 8_000, false);

        assertThat(budget.hardInputTokens()).isEqualTo(190_000);
        assertThat(budget.softTriggerTokens()).isEqualTo(161_500);
        assertThat(budget.windowEstimated()).isTrue();
        assertThat(budget.conservativeMode()).isFalse();
    }

    // ==================================================================
    // v1 兼容：旧运行按持久化策略版本保留 256k 触发上限
    // ==================================================================

    @Test
    void v1KeepsLegacy256kTriggerCapAndPerRequestCap() {
        var properties = properties(1_000_000);
        var window = window(properties);

        var capped = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 8_000, true);
        assertThat(capped.softTriggerTokens())
                .isEqualTo(AgentContextBudget.V1_LEGACY_SOFT_COMPACTION_TRIGGER_TOKENS);
        assertThat(capped.softTriggerTokens()).isEqualTo(256_000);
        assertThat(capped.compactionTargetTokens()).isEqualTo(128_000);
        assertThat(capped.hardInputTokens()).isEqualTo(990_000);
        assertThat(capped.availableInputTokens()).isEqualTo(50_000);
        assertThat(capped.binding()).isEqualTo(AgentContextBudget.BINDING_PER_REQUEST_CAP);
        assertThat(capped.windowEstimated()).isFalse();
        assertThat(capped.conservativeMode()).isFalse();

        var runLimited = AgentContextBudget.perRequest(properties, window, 30_000, 8_000, true);
        assertThat(runLimited.availableInputTokens()).isEqualTo(30_000);
        assertThat(runLimited.binding()).isEqualTo(AgentContextBudget.BINDING_RUN_INPUT_BUDGET);
        assertThat(runLimited.softTriggerTokens()).isEqualTo(256_000);
    }

    @Test
    void v1AndV2UseTheSameFormulaBelowTheLegacyCap() {
        // 128k 窗口的 H 派生触发线低于 256k：v1/v2 数值一致，只有大窗口才出现差异
        var properties = properties(128_000);
        var window = window(properties);
        var v1 = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 8_000, true);
        var v2 = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 8_000, false);

        assertThat(v1.softTriggerTokens()).isEqualTo(100_300);
        assertThat(v2.softTriggerTokens()).isEqualTo(100_300);
    }

    @Test
    void outputReserveUsesTheActualOutboundValue() {
        // 输出预留必须与本次实际出站值一致，不能用与实际发送不同的数字计算窗口
        var properties = properties(1_000_000);
        var window = window(properties);
        var withSmallOutput = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 4_000, false);
        var withLargeOutput = AgentContextBudget.perRequest(properties, window, Integer.MAX_VALUE, 32_000, false);

        assertThat(withSmallOutput.hardInputTokens()).isEqualTo(1_000_000 - 4_000 - 2_000);
        assertThat(withLargeOutput.hardInputTokens()).isEqualTo(1_000_000 - 32_000 - 2_000);
        assertThat(withSmallOutput.hardInputTokens()).isGreaterThan(withLargeOutput.hardInputTokens());
        assertThat(withSmallOutput.softTriggerTokens()).isGreaterThan(withLargeOutput.softTriggerTokens());
    }
}
