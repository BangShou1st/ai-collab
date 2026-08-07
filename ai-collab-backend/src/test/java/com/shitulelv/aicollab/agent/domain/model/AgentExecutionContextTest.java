package com.shitulelv.aicollab.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AgentExecutionContextTest {

    @Test
    void validContextCreation() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of());

        assertThat(ctx.runId()).isNotNull();
        assertThat(ctx.sessionId()).isNotNull();
        assertThat(ctx.projectId()).isNotNull();
        assertThat(ctx.requesterId()).isNotNull();
        assertThat(ctx.projectRole()).isEqualTo("MEMBER");
        assertThat(ctx.scheduled()).isFalse();
        assertThat(ctx.page()).isNotNull();
        assertThat(ctx.limits()).isNotNull();
        assertThat(ctx.depth()).isEqualTo(0);
        assertThat(ctx.proposals()).isNotNull().isEmpty();
    }

    @Test
    void nullRunIdThrows() {
        assertThatThrownBy(() -> new AgentExecutionContext(
                null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void nullSessionIdThrows() {
        assertThatThrownBy(() -> new AgentExecutionContext(
                UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    void nullProjectIdThrows() {
        assertThatThrownBy(() -> new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    void nullRequesterIdThrows() {
        assertThatThrownBy(() -> new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "MEMBER", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requesterId");
    }

    @Test
    void nullProjectRoleThrows() {
        assertThatThrownBy(() -> new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectRole");
    }

    @Test
    void nullLimitsThrows() {
        assertThatThrownBy(() -> new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), null, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limits");
    }

    @Test
    void nullProposalsThrows() {
        assertThatThrownBy(() -> new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposals");
    }

    @Test
    void budgetLimitsDelegate() {
        AgentRuntimeLimits limits = new AgentRuntimeLimits(
                10, 5, 8, 3,
                java.time.Duration.ofMinutes(2),
                java.time.Duration.ofSeconds(10),
                java.time.Duration.ofSeconds(15),
                32 * 1024, 50_000, 20_000);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), limits, 0, List.of());

        assertThat(ctx.maxSteps()).isEqualTo(10);
        assertThat(ctx.maxModelTurns()).isEqualTo(5);
        assertThat(ctx.maxToolCalls()).isEqualTo(8);
        assertThat(ctx.maxToolCallsPerTurn()).isEqualTo(3);
    }

    @Test
    void depthIsStored() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 3, List.of());
        assertThat(ctx.depth()).isEqualTo(3);
    }

    @Test
    void depthZeroIsDefault() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of());
        assertThat(ctx.depth()).isEqualTo(0);
    }
}
