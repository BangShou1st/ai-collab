package com.shitulelv.aicollab.infrastructure.ai;

public record ChatCompletionResult(
        String content,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs) {
}
