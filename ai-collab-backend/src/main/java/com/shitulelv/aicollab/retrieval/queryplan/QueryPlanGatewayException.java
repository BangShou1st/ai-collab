package com.shitulelv.aicollab.retrieval.queryplan;

/**
 * QueryPlan 网关调用失败（W4 冻结契约 §4）。
 *
 * <p>{@code message} 只允许是固定的本地描述，<b>绝不</b>携带 query、候选正文、
 * 过滤值、服务令牌或上游响应原文。</p>
 */
public class QueryPlanGatewayException extends RuntimeException {

    /** 失败原因：本波只有这五类，便于测试与后续调用方按类降级。 */
    public enum Reason {
        TIMEOUT,
        UNAVAILABLE,
        SCHEMA,
        HTTP,
        UNEXPECTED
    }

    private final Reason reason;

    public QueryPlanGatewayException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
