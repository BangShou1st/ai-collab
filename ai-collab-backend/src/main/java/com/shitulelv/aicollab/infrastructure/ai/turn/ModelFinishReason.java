package com.shitulelv.aicollab.infrastructure.ai.turn;

/**
 * 统一供应商结束原因。
 * 不得让 Provider 的原始任意字符串贯穿 Agent Runtime。
 */
public enum ModelFinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    ERROR,
    UNKNOWN
}
