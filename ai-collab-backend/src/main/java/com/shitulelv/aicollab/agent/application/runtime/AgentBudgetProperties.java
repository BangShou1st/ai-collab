package com.shitulelv.aicollab.agent.application.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 预算软阈值配置（预算治理 v3，设计文档 §4.2）。
 *
 * <p>{@code soft-input-token-cap} 是 run 级累计输入的<b>引导性软顶，非硬准入</b>：
 * 达到阈值只触发收敛提示注入与 Micrometer 指标（{@code agent.budget.soft.warning}），
 * 不拒绝请求、不终止运行。v2 语义下 run 的累计 token 上限为 NULL（只记账不管控），
 * 软顶从本配置取得，作为软阈值分级的分母。0 表示关闭软阈值（负数防御性归零，
 * 配置错误不应阻断应用启动）。</p>
 *
 * <p>{@code session-aggregate-token-cap} 是会话聚合 token 硬顶（预算治理 v3，
 * 设计文档 §4.1）：新会话创建时写入 {@code agent_session.max_aggregate_tokens}，
 * 由 {@code AgentSessionBudgetPolicy} 分级、模型轮准入消费。0 或负数防御性归零
 * （归零后新会话写 NULL = 不限，与存量会话语义一致）。</p>
 */
@ConfigurationProperties("agent.budget")
public record AgentBudgetProperties(
        @DefaultValue("150000") long softInputTokenCap,
        @DefaultValue("2000000") long sessionAggregateTokenCap) {

    public AgentBudgetProperties {
        // 负数防御性归零：等价于关闭软阈值，与 AgentBudgetThresholdPolicy 的防御语义一致
        if (softInputTokenCap < 0) softInputTokenCap = 0;
        // 负数防御性归零：会话聚合上限 0 由创建路径转写为 NULL（不限），分级策略同样视为关闭
        if (sessionAggregateTokenCap < 0) sessionAggregateTokenCap = 0;
    }

    public static AgentBudgetProperties defaults() {
        return new AgentBudgetProperties(150_000, 2_000_000);
    }
}
