package com.shitulelv.aicollab.retrieval.rerank;

/**
 * Rerank 网关调用失败。
 *
 * <p>{@code message} 只允许是固定的本地描述（例如 "rerank worker 连接超时"），
 * <b>绝不允许</b>携带 query、候选正文、服务令牌或上游响应原文——异常会进日志，
 * 也可能被上层拼接进对外错误。</p>
 */
public class RerankGatewayException extends RuntimeException {

    private final RerankGatewayErrorCode code;
    private final boolean retryable;

    public RerankGatewayException(RerankGatewayErrorCode code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public RerankGatewayErrorCode code() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
