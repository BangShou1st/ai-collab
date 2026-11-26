package com.shitulelv.aicollab.internal.ai;

/**
 * 内部模型网关错误码（冻结契约 §1/§2/§7.1 的九码子集）。
 *
 * <p>响应信封固定为 {@code {code, message, retryable, details?}}，<b>不</b>使用业务
 * {@code ApiResponse} 包装——内部端点的调用方是 Python worker，需要稳定、扁平的机器契约。</p>
 */
public enum InternalAiErrorCode {

    INVALID_REQUEST(422, false, "请求参数无效"),
    SCHEMA_VIOLATION(422, false, "请求不符合契约"),
    MODEL_UNAVAILABLE(503, true, "模型服务不可用"),
    MODEL_RATE_LIMITED(429, true, "模型服务限流"),
    DEADLINE_EXCEEDED(504, true, "模型调用超时"),
    /** W5 Job 租约 fence：心跳/结果携带的 epoch 与当前租约不一致（仅内部端点使用）。 */
    STALE_LEASE(409, false, "租约已失效"),
    /** P1 §2.2：凭据租借校验不通过（不区分具体原因）。 */
    LEASE_FORBIDDEN(403, false, "凭据租借被拒绝"),
    /** P1 §2.2：解析不到可用提供商。 */
    AI_PROVIDER_UNAVAILABLE(503, true, "模型服务尚未配置或暂时不可用"),
    /**
     * P1 修订 R3 §11：提供商生成参数（temperature / maxOutputTokens）越界。同为 503，但不可重试——
     * 配置不改就永远失败。message 固定，不含参数值与任何凭据。
     */
    AI_PROVIDER_PARAMS_INVALID(503, false, "模型提供商的生成参数配置不合格，请调整后重试"),
    /** P1 §4.3 / R1 §9.3：claim 条件不满足（已被认领、epoch 过期、状态不对、任务不存在）。 */
    TASK_CLAIM_CONFLICT(409, false, "任务认领冲突"),
    /** P1 §4.3：epoch 或 leaseToken 与当前租约不符。 */
    TASK_EPOCH_STALE(409, false, "任务租约已失效"),
    INTERNAL(500, false, "内部错误");

    private final int httpStatus;
    private final boolean retryable;
    private final String message;

    InternalAiErrorCode(int httpStatus, boolean retryable, String message) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    public String message() {
        return message;
    }
}
