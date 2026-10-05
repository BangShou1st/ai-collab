package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

/** Response metadata only: never stores provider body, reasoning, headers or credentials. */
public final class ProviderResponseFailure extends BusinessException implements UsageCarryingFailure {
    private final Integer promptTokens;
    private final Integer completionTokens;

    public ProviderResponseFailure(ErrorCode code, String diagnostic, Integer input, Integer output) {
        super(code, diagnostic);
        promptTokens = input;
        completionTokens = output;
    }

    public Integer promptTokens() { return promptTokens; }
    public Integer completionTokens() { return completionTokens; }
    @Override public Integer carriedPromptTokens() { return promptTokens; }
    @Override public Integer carriedCompletionTokens() { return completionTokens; }
}
