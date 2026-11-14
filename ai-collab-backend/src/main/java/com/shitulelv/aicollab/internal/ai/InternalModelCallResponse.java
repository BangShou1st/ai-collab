package com.shitulelv.aicollab.internal.ai;

/** 内部模型网关成功响应（冻结契约 §2）：扁平结构，不包 ApiResponse。 */
public record InternalModelCallResponse(
        int schemaVersion,
        String content,
        Usage usage,
        String modelAlias,
        long latencyMs) {

    /** usage 缺失标 UNKNOWN，不填 0 冒充已知。 */
    public record Usage(Integer inputTokens, Integer outputTokens, String source) {
    }
}
