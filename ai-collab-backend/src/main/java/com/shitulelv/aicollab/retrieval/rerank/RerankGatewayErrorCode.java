package com.shitulelv.aicollab.retrieval.rerank;

/**
 * Rerank 网关失败分类。取值与冻结契约 §1 的错误码对齐，便于 worker 侧同码对账。
 */
public enum RerankGatewayErrorCode {
    /** 连接失败、502/503/504 或其它 5xx（可重试性由异常自带标记决定）。 */
    MODEL_UNAVAILABLE,
    /** 429：worker 侧限流。 */
    MODEL_RATE_LIMITED,
    /** 读超时：超过 {@code deadlineMs + 5s} 仍未拿到响应。 */
    DEADLINE_EXCEEDED,
    /** 非法 JSON、schemaVersion 不匹配、分数越界等契约违规。 */
    SCHEMA_VIOLATION,
    /** 其它 4xx：请求本身被拒绝。 */
    INVALID_REQUEST,
    /** 非预期内部错误（序列化失败等）。 */
    INTERNAL
}
