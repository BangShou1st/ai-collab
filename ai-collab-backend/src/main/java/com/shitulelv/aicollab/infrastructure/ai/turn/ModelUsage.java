package com.shitulelv.aicollab.infrastructure.ai.turn;

/**
 * 统一 Token 使用量。供应商未返回时允许为 null，但不得伪造。
 */
public record ModelUsage(Integer inputTokens, Integer outputTokens) {
    public ModelUsage {
        if (inputTokens != null && inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens 不能为负");
        }
        if (outputTokens != null && outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens 不能为负");
        }
    }
}
