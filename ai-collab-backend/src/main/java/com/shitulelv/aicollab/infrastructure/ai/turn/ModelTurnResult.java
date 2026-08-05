package com.shitulelv.aicollab.infrastructure.ai.turn;

import java.util.List;

/**
 * 统一模型轮次结果合同。
 * 支持：只有文本、只有 Tool Call、文本和 Tool Call 同时存在、多个 Tool Call。
 */
public record ModelTurnResult(
        String content,
        List<ModelToolCall> toolCalls,
        ModelFinishReason finishReason,
        ModelUsage usage,
        String provider,
        String model,
        long latencyMs) {
    public ModelTurnResult {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("模型既没有文本也没有工具调用");
        }
    }
}
