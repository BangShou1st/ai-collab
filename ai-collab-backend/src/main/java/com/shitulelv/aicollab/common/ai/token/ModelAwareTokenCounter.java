package com.shitulelv.aicollab.common.ai.token;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一 Token 计数器：协议开销包装层。
 *
 * <p>J0-1 起文本 → token 只有 {@link TokenEstimator} 一个实现。provider/model 参数
 * 为 API 兼容而保留，但<b>不改变计量数值</b>——不同模型名称不再产生不同估算，
 * 也不存在任何"原生统计"伪装：提供商上报的真实 usage 只从 Provider 响应进入结算，
 * 本计数器的一切结果都明确标记为 {@link TokenCountSource#ESTIMATE}。</p>
 *
 * <ul>
 *   <li>文本：{@code TokenCount.estimate(TokenEstimator.estimate(text), "CJK_WEIGHTED_V1")}；</li>
 *   <li>消息：每条 4 token 协议开销 + 文本估算，末尾 3 token primer，basis=MESSAGE_PROTOCOL；</li>
 *   <li>工具定义：每个 16 token Schema 封装开销 + 文本估算，basis=TOOL_SCHEMA_PROTOCOL。</li>
 * </ul>
 */
@Component
public class ModelAwareTokenCounter implements TokenCounter {

    static final String TEXT_BASIS = "CJK_WEIGHTED_V1";
    static final String MESSAGE_BASIS = "MESSAGE_PROTOCOL";
    static final String TOOL_SCHEMA_BASIS = "TOOL_SCHEMA_PROTOCOL";

    /** 每条消息的协议包装开销（角色标记、分隔符等）。 */
    private static final int MESSAGE_PROTOCOL_OVERHEAD = 4;
    /** 请求末尾的 primer 开销。 */
    private static final int MESSAGE_PRIMER_OVERHEAD = 3;
    /** 单个工具定义的 Schema 封装与调用契约开销。 */
    private static final int TOOL_SCHEMA_OVERHEAD = 16;

    @Override
    public TokenCount countText(String text, String providerType, String modelName) {
        if (text == null || text.isEmpty()) {
            return TokenCount.zero();
        }
        // provider/model 仅为 API 兼容保留：同一文本在任何模型下得到同一估算。
        return TokenCount.estimate(TokenEstimator.estimate(text), TEXT_BASIS);
    }

    @Override
    public TokenCount countMessages(List<?> messages, String providerType, String modelName) {
        if (messages == null || messages.isEmpty()) {
            return TokenCount.zero();
        }
        int total = MESSAGE_PRIMER_OVERHEAD;
        for (Object message : messages) {
            total += MESSAGE_PROTOCOL_OVERHEAD;
            if (message != null) {
                total += TokenEstimator.estimate(message.toString());
            }
        }
        return TokenCount.estimate(total, MESSAGE_BASIS);
    }

    @Override
    public TokenCount countToolDefinitions(List<?> tools, String providerType, String modelName) {
        if (tools == null || tools.isEmpty()) {
            return TokenCount.zero();
        }
        int total = 0;
        for (Object tool : tools) {
            total += TOOL_SCHEMA_OVERHEAD;
            if (tool != null) {
                total += TokenEstimator.estimate(tool.toString());
            }
        }
        return TokenCount.estimate(total, TOOL_SCHEMA_BASIS);
    }
}
