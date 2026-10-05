package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.infrastructure.ai.model.UsageCarryingFailure;

/** Legacy 决策解析失败：保留原 IllegalArgumentException 语义（格式修复路径），
 *  同时携带提供商已上报的响应用量，结算时优先保留真实值。 */
public final class LegacyDecisionParseFailure extends IllegalArgumentException implements UsageCarryingFailure {
    private final Integer promptTokens;
    private final Integer completionTokens;

    public LegacyDecisionParseFailure(String message, Integer promptTokens, Integer completionTokens) {
        super(message);
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    @Override public Integer carriedPromptTokens() { return promptTokens; }

    @Override public Integer carriedCompletionTokens() { return completionTokens; }
}
