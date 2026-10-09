package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;

/** 异常发生时提供商已上报的响应用量：结算时优先保留真实值，缺失侧再估算或标未知。 */
public interface UsageCarryingFailure {
    Integer carriedPromptTokens();

    Integer carriedCompletionTokens();

    default ModelUsage carriedUsage() {
        return new ModelUsage(carriedPromptTokens(), carriedCompletionTokens());
    }
}
