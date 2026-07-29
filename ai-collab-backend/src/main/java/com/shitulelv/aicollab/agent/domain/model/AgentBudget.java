package com.shitulelv.aicollab.agent.domain.model;

public record AgentBudget(
        int maxSteps,
        int maxToolCalls,
        int maxChildren,
        int maxInputTokens,
        int maxOutputTokens,
        int stepsUsed,
        int toolCallsUsed,
        int childrenUsed,
        int inputTokensUsed,
        int outputTokensUsed,
        boolean tokenUsageEstimated) {

    public AgentBudget {
        if (maxSteps < 1 || maxToolCalls < 0 || maxChildren < 0
                || maxInputTokens < 1 || maxOutputTokens < 1
                || stepsUsed < 0 || toolCallsUsed < 0 || childrenUsed < 0
                || inputTokensUsed < 0 || outputTokensUsed < 0) {
            throw new IllegalArgumentException("Agent 预算不能为负且上限必须有效");
        }
    }

    public static AgentBudget defaults() {
        return new AgentBudget(12, 8, 3, 50_000, 20_000, 0, 0, 0, 0, 0, false);
    }

    public AgentBudget withUsage(
            int steps, int tools, int children, int inputTokens, int outputTokens, boolean estimated) {
        return new AgentBudget(
                maxSteps, maxToolCalls, maxChildren, maxInputTokens, maxOutputTokens,
                steps, tools, children, inputTokens, outputTokens, estimated);
    }

    public AgentBudget debitStep() {
        if (stepsUsed >= maxSteps) {
            throw new IllegalStateException("Agent 步骤预算已耗尽");
        }
        return withUsage(stepsUsed + 1, toolCallsUsed, childrenUsed,
                inputTokensUsed, outputTokensUsed, tokenUsageEstimated);
    }

    public AgentBudget debitTool() {
        if (toolCallsUsed >= maxToolCalls) {
            throw new IllegalStateException("Agent 工具预算已耗尽");
        }
        return withUsage(stepsUsed, toolCallsUsed + 1, childrenUsed,
                inputTokensUsed, outputTokensUsed, tokenUsageEstimated);
    }

    public AgentBudget debitChild() {
        if (childrenUsed >= maxChildren) {
            throw new IllegalStateException("Agent 专家预算已耗尽");
        }
        return withUsage(stepsUsed, toolCallsUsed, childrenUsed + 1,
                inputTokensUsed, outputTokensUsed, tokenUsageEstimated);
    }

    public AgentBudget debitTokens(int input, int output, boolean estimated) {
        if (input < 0 || output < 0
                || inputTokensUsed + input > maxInputTokens
                || outputTokensUsed + output > maxOutputTokens) {
            throw new IllegalStateException("Agent Token 预算已耗尽");
        }
        return withUsage(stepsUsed, toolCallsUsed, childrenUsed,
                inputTokensUsed + input, outputTokensUsed + output,
                tokenUsageEstimated || estimated);
    }
}
