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
 */
@ConfigurationProperties("agent.budget")
public record AgentBudgetProperties(
        @DefaultValue("150000") long softInputTokenCap) {

    public AgentBudgetProperties {
        // 负数防御性归零：等价于关闭软阈值，与 AgentBudgetThresholdPolicy 的防御语义一致
        if (softInputTokenCap < 0) softInputTokenCap = 0;
    }

    public static AgentBudgetProperties defaults() {
        return new AgentBudgetProperties(150_000);
    }
}
