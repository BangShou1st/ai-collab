package com.shitulelv.aicollab.common.ai.token;

import java.util.List;

/**
 * Token 计数器契约。
 *
 * <p>J0-1 起文本计量统一由 {@link TokenEstimator} 承担；provider/model 参数为 API
 * 兼容保留，不改变计量数值。实现必须把本地计算显式标注为估算来源，不能冒充提供商
 * 上报或精确 tokenizer 结果。</p>
 */
public interface TokenCounter {

    /**
     * 计算单段纯文本的 Token 数（统一 CJK 感知估算）
     */
    TokenCount countText(String text, String providerType, String modelName);

    /**
     * 默认入口：provider/model 为空，仍走同一估算实现
     */
    default TokenCount countText(String text) {
        return countText(text, null, null);
    }

    /**
     * 计算包含角色、内容的消息列表的 Token 数及协议开销
     */
    TokenCount countMessages(List<?> messages, String providerType, String modelName);

    /**
     * 工具定义的 Schema 协议开销估算
     */
    TokenCount countToolDefinitions(List<?> tools, String providerType, String modelName);
}
