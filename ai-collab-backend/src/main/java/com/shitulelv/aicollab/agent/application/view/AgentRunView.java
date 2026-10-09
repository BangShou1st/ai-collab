package com.shitulelv.aicollab.agent.application.view;

import com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentRunView(
        UUID id,
        UUID sessionId,
        UUID projectId,
        UUID requesterId,
        UUID parentRunId,
        String role,
        int depth,
        String goal,
        AgentRunStatus status,
        int maxSteps,
        int maxToolCalls,
        int maxChildren,
        /**
         * 运行<b>累计</b>输入 token 上限；{@code null} 表示无累计上限（只统计用量）。
         * 与模型配置的单次最大输出是两层不同限制，不在此列。
         */
        Integer maxInputTokens,
        /** 运行<b>累计</b>输出 token 上限；{@code null} 表示无累计上限（只统计用量）。 */
        Integer maxOutputTokens,
        int stepsUsed,
        int toolCallsUsed,
        int childrenUsed,
        int inputTokensUsed,
        int outputTokensUsed,
        /** 真实消耗（可高于 max_*_tokens 上限）；used 列在 v1 保持预算语义（封顶）。 */
        long inputTokensActual,
        long outputTokensActual,
        boolean tokenUsageEstimated,
        boolean scheduled,
        boolean correctionAttempted,
        int retryCount,
        String errorCode,
        String planJson,
        String pageContextJson,
        String skillCode,
        int version,
        /** 资源策略版本（V64）：1=既有累计额度语义，2=累计只统计 + 父子独立执行额度。 */
        int contextPolicyVersion,
        /**
         * 推进计数语义（V63）：true=COMBINED（旧语义，工具结果逐项占推进步），
         * false=SEPARATED（新语义，工具结果只计 tool_calls_used）。
         * 委派受理/可见性的推进步成本依赖这一真实事实，不能固定假定其一。
         */
        boolean combinedBudgetSemantics,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** 本运行是否执行累计 token 上限（只有 v1 执行；v2 只统计）。 */
    public boolean enforcesCumulativeTokenLimits() {
        return AgentResourcePolicy.enforcesCumulativeTokenLimits(contextPolicyVersion);
    }

    /**
     * 兼容构造器（历史字段形状）：按既有 v1 语义与 SEPARATED 计数默认值填充新增字段。
     *
     * <p>持久化读取一律走规范构造器（策略版本与预算语义来自 {@code agent_run}），
     * 本构造器只服务手工构造运行视图的既有调用点/测试，保证新增策略维度不会
     * 悄悄改变它们的既有含义。</p>
     */
    public AgentRunView(
            UUID id, UUID sessionId, UUID projectId, UUID requesterId, UUID parentRunId,
            String role, int depth, String goal, AgentRunStatus status,
            int maxSteps, int maxToolCalls, int maxChildren,
            int maxInputTokens, int maxOutputTokens,
            int stepsUsed, int toolCallsUsed, int childrenUsed,
            int inputTokensUsed, int outputTokensUsed,
            long inputTokensActual, long outputTokensActual,
            boolean tokenUsageEstimated, boolean scheduled, boolean correctionAttempted,
            int retryCount, String errorCode, String planJson, String pageContextJson,
            String skillCode, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, sessionId, projectId, requesterId, parentRunId, role, depth, goal, status,
                maxSteps, maxToolCalls, maxChildren, maxInputTokens, maxOutputTokens,
                stepsUsed, toolCallsUsed, childrenUsed, inputTokensUsed, outputTokensUsed,
                inputTokensActual, outputTokensActual, tokenUsageEstimated, scheduled,
                correctionAttempted, retryCount, errorCode, planJson, pageContextJson,
                skillCode, version, AgentResourcePolicy.V1, false, createdAt, updatedAt);
    }
}
