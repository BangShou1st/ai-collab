package com.shitulelv.aicollab.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class AgentRuntimeLimitsTest {

    @Test
    void defaultLimits() {
        AgentRuntimeLimits limits = AgentRuntimeLimits.defaults();
        assertThat(limits.maxSteps()).isEqualTo(16);
        assertThat(limits.maxModelTurns()).isEqualTo(8);
        assertThat(limits.maxToolCalls()).isEqualTo(12);
        assertThat(limits.maxToolCallsPerTurn()).isEqualTo(4);
        assertThat(limits.maxRunDuration()).isEqualTo(Duration.ofMinutes(3));
        assertThat(limits.maxToolResultBytes()).isEqualTo(32 * 1024);
        assertThat(limits.maxInputTokens()).isEqualTo(100_000);
        assertThat(limits.maxOutputTokens()).isEqualTo(32_000);
    }

    @Test
    void projectHealthLimits() {
        AgentRuntimeLimits limits = AgentRuntimeLimits.forSkill("PROJECT_HEALTH");
        assertThat(limits.maxSteps()).isEqualTo(8);
        assertThat(limits.maxModelTurns()).isEqualTo(4);
        assertThat(limits.maxToolCalls()).isEqualTo(6);
    }

    @Test
    void weeklyReportLimits() {
        AgentRuntimeLimits limits = AgentRuntimeLimits.forSkill("WEEKLY_REPORT");
        assertThat(limits.maxSteps()).isEqualTo(12);
        assertThat(limits.maxModelTurns()).isEqualTo(6);
        assertThat(limits.maxToolCalls()).isEqualTo(8);
    }

    @Test
    void unknownSkillReturnsDefaults() {
        AgentRuntimeLimits limits = AgentRuntimeLimits.forSkill("UNKNOWN_SKILL");
        assertThat(limits).isEqualTo(AgentRuntimeLimits.defaults());
    }

    @Test
    void invalidMaxStepsThrows() {
        assertThatThrownBy(() -> new AgentRuntimeLimits(
                0, 8, 12, 4, Duration.ofMinutes(3), Duration.ofSeconds(10),
                Duration.ofSeconds(15), 32 * 1024, 100_000, 32_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSteps");
    }

    @Test
    void invalidMaxModelTurnsThrows() {
        assertThatThrownBy(() -> new AgentRuntimeLimits(
                16, 0, 12, 4, Duration.ofMinutes(3), Duration.ofSeconds(10),
                Duration.ofSeconds(15), 32 * 1024, 100_000, 32_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxModelTurns");
    }

    @Test
    void invalidMaxToolCallsThrows() {
        assertThatThrownBy(() -> new AgentRuntimeLimits(
                16, 8, 0, 4, Duration.ofMinutes(3), Duration.ofSeconds(10),
                Duration.ofSeconds(15), 32 * 1024, 100_000, 32_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxToolCalls");
    }

    @Test
    void invalidMaxToolCallsPerTurnThrows() {
        assertThatThrownBy(() -> new AgentRuntimeLimits(
                16, 8, 12, 0, Duration.ofMinutes(3), Duration.ofSeconds(10),
                Duration.ofSeconds(15), 32 * 1024, 100_000, 32_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxToolCallsPerTurn");
    }

    @Test
    void invalidMaxRunDurationThrows() {
        assertThatThrownBy(() -> new AgentRuntimeLimits(
                16, 8, 12, 4, null, Duration.ofSeconds(10),
                Duration.ofSeconds(15), 32 * 1024, 100_000, 32_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRunDuration");
    }

    @Test
    void invalidMaxToolResultBytesThrows() {
        assertThatThrownBy(() -> new AgentRuntimeLimits(
                16, 8, 12, 4, Duration.ofMinutes(3), Duration.ofSeconds(10),
                Duration.ofSeconds(15), 100, 100_000, 32_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxToolResultBytes");
    }

    @Test
    void invalidMaxInputTokensThrows() {
        assertThatThrownBy(() -> new AgentRuntimeLimits(
                16, 8, 12, 4, Duration.ofMinutes(3), Duration.ofSeconds(10),
                Duration.ofSeconds(15), 32 * 1024, 100, 32_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInputTokens");
    }

    @Test
    void invalidMaxOutputTokensThrows() {
        assertThatThrownBy(() -> new AgentRuntimeLimits(
                16, 8, 12, 4, Duration.ofMinutes(3), Duration.ofSeconds(10),
                Duration.ofSeconds(15), 32 * 1024, 100_000, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputTokens");
    }
}
