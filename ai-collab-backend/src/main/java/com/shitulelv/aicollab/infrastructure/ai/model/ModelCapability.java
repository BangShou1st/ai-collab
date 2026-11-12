package com.shitulelv.aicollab.infrastructure.ai.model;

public enum ModelCapability {
    CHAT,
    STREAMING,
    STRUCTURED_OUTPUT,
    NATIVE_TOOLS,
    USAGE,
    /** 非 Chat 能力 Provider（当前为 RERANK）也会被共享 Provider 行持久化；显式保留该能力值。 */
    RERANK,
    /** 声明后 Gemini 保持模型默认思考行为（不发 thinkingConfig）；
     *  未声明则 Gemini 适配器显式关闭思考链（thinkingBudget=0），保证确定性输出不被思考 token 挤占。 */
    THINKING
}
