package com.shitulelv.aicollab.agent.job;

/**
 * AI Job 业务异常（不依赖 Web 层错误码）。
 *
 * <p>内部端点适配器把它映射成扁平错误信封；{@link Kind#STALE_LEASE} 必须映射为
 * HTTP 409 + {@code STALE_LEASE}，其余按 {@code INVALID_REQUEST}/404 处理。</p>
 */
public class AiJobException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        STALE_LEASE,
        INVALID_STATE,
        INVALID_REQUEST
    }

    private final Kind kind;

    public AiJobException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
