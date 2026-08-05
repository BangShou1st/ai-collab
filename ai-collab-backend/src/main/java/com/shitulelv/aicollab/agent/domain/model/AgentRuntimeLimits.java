package com.shitulelv.aicollab.agent.domain.model;

import java.time.Duration;

/**
 * 运行时预算限制。模型不能修改这些值。
 */
public record AgentRuntimeLimits(
        int maxSteps,
        int maxModelTurns,
        int maxToolCalls,
        int maxToolCallsPerTurn,
        Duration maxRunDuration,
        Duration internalToolTimeout,
        Duration mcpToolTimeout,
        int maxToolResultBytes,
        int maxInputTokens,
        int maxOutputTokens) {

    public AgentRuntimeLimits {
        if (maxSteps < 1) throw new IllegalArgumentException("maxSteps 必须 >= 1");
        if (maxModelTurns < 1) throw new IllegalArgumentException("maxModelTurns 必须 >= 1");
        if (maxToolCalls < 1) throw new IllegalArgumentException("maxToolCalls 必须 >= 1");
        if (maxToolCallsPerTurn < 1) throw new IllegalArgumentException("maxToolCallsPerTurn 必须 >= 1");
        if (maxRunDuration == null || maxRunDuration.isNegative()) throw new IllegalArgumentException("maxRunDuration 无效");
        if (internalToolTimeout == null || internalToolTimeout.isNegative()) throw new IllegalArgumentException("internalToolTimeout 无效");
        if (mcpToolTimeout == null || mcpToolTimeout.isNegative()) throw new IllegalArgumentException("mcpToolTimeout 无效");
        if (maxToolResultBytes < 1024) throw new IllegalArgumentException("maxToolResultBytes 必须 >= 1024");
        if (maxInputTokens < 1000) throw new IllegalArgumentException("maxInputTokens 必须 >= 1000");
        if (maxOutputTokens < 1000) throw new IllegalArgumentException("maxOutputTokens 必须 >= 1000");
    }

    public static AgentRuntimeLimits defaults() {
        return new AgentRuntimeLimits(
                16, 8, 12, 4,
                Duration.ofMinutes(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                32 * 1024,
                100_000,
                32_000);
    }

    public static AgentRuntimeLimits forSkill(String skillCode) {
        return switch (skillCode) {
            case "PROJECT_HEALTH" -> new AgentRuntimeLimits(
                    8, 4, 6, 4,
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    80_000,
                    24_000);
            case "WEEKLY_REPORT" -> new AgentRuntimeLimits(
                    12, 6, 8, 4,
                    Duration.ofMinutes(3),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    100_000,
                    32_000);
            case "MEETING_TO_TASKS" -> new AgentRuntimeLimits(
                    10, 5, 8, 4,
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    80_000,
                    32_000);
            case "ITERATION_PLANNING" -> new AgentRuntimeLimits(
                    12, 6, 10, 4,
                    Duration.ofMinutes(3),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    100_000,
                    32_000);
            case "DELIVERY_READINESS" -> new AgentRuntimeLimits(
                    8, 4, 6, 4,
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    80_000,
                    24_000);
            default -> defaults();
        };
    }
}
