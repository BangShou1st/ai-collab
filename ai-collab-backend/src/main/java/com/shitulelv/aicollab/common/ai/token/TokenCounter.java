package com.shitulelv.aicollab.common.ai.token;

import java.util.List;

public interface TokenCounter {

    /**
     * 计算单段纯文本的 Token 数
     */
    TokenCount countText(String text, String providerType, String modelName);

    /**
     * 默认模型感知的文本 Token 计算
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
