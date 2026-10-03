package com.shitulelv.aicollab.agent.application.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Agent 单次请求上下文预算配置。
 *
 * <p>预算公式：{@code min(模型窗口 - 输出预留 - 安全余量, 运行剩余输入预算, 应用单次上限)}。
 * 模型窗口按"提供商类型:模型名"或"模型名"在 {@code window-overrides} 中匹配；
 * 未匹配到时视为窗口未知，此时只受运行剩余预算与应用单次上限约束（保守应用上限，
 * 与历史行为一致），并在事件中标记估算。</p>
 */
@ConfigurationProperties("agent.context")
public record AgentContextProperties(
        @DefaultValue("true") boolean composerV2,
        @DefaultValue("50000") int perRequestInputCap,
        @DefaultValue("8000") int outputReserveTokens,
        @DefaultValue("2000") int safetyMarginTokens,
        Map<String, Integer> windowOverrides) {

    public AgentContextProperties {
        if (perRequestInputCap < 4000) throw new IllegalArgumentException("per-request-input-cap 必须 >= 4000");
        if (outputReserveTokens < 0) throw new IllegalArgumentException("output-reserve-tokens 不能为负");
        if (safetyMarginTokens < 0) throw new IllegalArgumentException("safety-margin-tokens 不能为负");
        windowOverrides = windowOverrides == null ? Map.of() : Map.copyOf(windowOverrides);
    }

    public static AgentContextProperties defaults() {
        return new AgentContextProperties(true, 50_000, 8_000, 2_000, Map.of());
    }
}
