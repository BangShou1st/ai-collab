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
}
