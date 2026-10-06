package com.shitulelv.aicollab.infrastructure.ai.turn;

import java.util.List;

/**
 * 统一模型轮次结果合同。
 * 支持：只有文本、只有 Tool Call、文本和 Tool Call 同时存在、多个 Tool Call。
 *
 * <p>{@code finalizing} 是本轮请求<b>实际采用</b>的强制收尾意图（消息组装与
 * {@code needsFinalRequest} 调整之后、真正出站时使用的值），随响应一起持久化，
 * 供接管恢复按原请求语义处理已保存结果；不使用正文猜测意图。</p>
 *
 * <p>{@code null} 表示该记录早于本元数据（历史未收口记录）：调用方必须保守回退到
 * 按当前收敛策略推导，并在需要时明确说明该记录的原请求意图已不可还原，
 * 既不一律记成功，也不丢弃已有答案。</p>
 */
public record ModelTurnResult(
        String content,
        List<ModelToolCall> toolCalls,
        ModelFinishReason finishReason,
        ModelUsage usage,
        String provider,
        String model,
        long latencyMs,
        Boolean finalizing) {
    public ModelTurnResult {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("模型既没有文本也没有工具调用");
        }
    }

    /** 兼容既有调用方（适配器、摘要、测试）：不含持久化意图元数据。 */
    public ModelTurnResult(String content, List<ModelToolCall> toolCalls, ModelFinishReason finishReason,
            ModelUsage usage, String provider, String model, long latencyMs) {
        this(content, toolCalls, finishReason, usage, provider, model, latencyMs, null);
    }

    /** 绑定本次请求真实采用的强制收尾意图后再持久化。 */
    public ModelTurnResult withFinalizing(boolean finalizing) {
        return new ModelTurnResult(content, toolCalls, finishReason, usage, provider, model, latencyMs, finalizing);
    }
}
