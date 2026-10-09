package com.shitulelv.aicollab.agent.domain.model;

import java.time.Duration;

/**
 * 运行时预算限制。模型不能修改这些值。
 *
 * <p>{@code maxInputTokens} / {@code maxOutputTokens} 是<b>运行累计</b> token 上限，
 * 与模型配置的<b>单次</b>最大输出是两层不同限制。新策略（{@link AgentResourcePolicy#V2}）
 * 下两者为 {@code null}：累计用量只统计，不限额、不参与准入/收敛/停机。
 * {@code null} 明确表达"无累计上限"，不用 0 或超大整数冒充。</p>
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
        Integer maxInputTokens,
        Integer maxOutputTokens) {

    public AgentRuntimeLimits {
        if (maxSteps < 1) throw new IllegalArgumentException("maxSteps 必须 >= 1");
        if (maxModelTurns < 1) throw new IllegalArgumentException("maxModelTurns 必须 >= 1");
        if (maxToolCalls < 1) throw new IllegalArgumentException("maxToolCalls 必须 >= 1");
        if (maxToolCallsPerTurn < 1) throw new IllegalArgumentException("maxToolCallsPerTurn 必须 >= 1");
        if (maxRunDuration == null || maxRunDuration.isNegative()) throw new IllegalArgumentException("maxRunDuration 无效");
        if (internalToolTimeout == null || internalToolTimeout.isNegative()) throw new IllegalArgumentException("internalToolTimeout 无效");
        if (mcpToolTimeout == null || mcpToolTimeout.isNegative()) throw new IllegalArgumentException("mcpToolTimeout 无效");
        if (maxToolResultBytes < 1024) throw new IllegalArgumentException("maxToolResultBytes 必须 >= 1024");
        // null 是"无累计上限"的显式表达（新策略）；非 null 时必须是有意义的正额度
        if (maxInputTokens != null && maxInputTokens < 1000) throw new IllegalArgumentException("maxInputTokens 必须 >= 1000");
        if (maxOutputTokens != null && maxOutputTokens < 1000) throw new IllegalArgumentException("maxOutputTokens 必须 >= 1000");
    }

    public static AgentRuntimeLimits defaults() {
        return new AgentRuntimeLimits(
                16, 8, 12, 4,
                Duration.ofMinutes(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                32 * 1024,
                50_000,
                20_000);
    }

    public static AgentRuntimeLimits forSkill(String skillCode) {
        if (skillCode == null || skillCode.isBlank()) return defaults();
        return switch (skillCode) {
            case "PROJECT_HEALTH" -> new AgentRuntimeLimits(
                    8, 4, 6, 4,
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    40_000,
                    16_000);
            case "WEEKLY_REPORT" -> new AgentRuntimeLimits(
                    12, 6, 8, 4,
                    Duration.ofMinutes(3),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    50_000,
                    20_000);
            case "MEETING_TO_TASKS" -> new AgentRuntimeLimits(
                    10, 5, 8, 4,
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    40_000,
                    20_000);
            case "ITERATION_PLANNING" -> new AgentRuntimeLimits(
                    24, 8, 16, 4,
                    Duration.ofMinutes(5),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    50_000,
                    20_000);
            case "DELIVERY_READINESS" -> new AgentRuntimeLimits(
                    8, 4, 6, 4,
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32 * 1024,
                    40_000,
                    16_000);
            default -> defaults();
        };
    }

    /**
     * 按运行策略版本解析限制：v2 使用新策略的独立执行额度（根/子不同），
     * v1 沿用既有 Skill 额度语义（恢复与暂停续跑不重解释）。
     */
    public static AgentRuntimeLimits forRun(int contextPolicyVersion, int depth, String skillCode) {
        return AgentResourcePolicy.normalize(contextPolicyVersion) == AgentResourcePolicy.V2
                ? AgentResourcePolicy.v2Limits(depth)
                : forSkill(skillCode);
    }
}
