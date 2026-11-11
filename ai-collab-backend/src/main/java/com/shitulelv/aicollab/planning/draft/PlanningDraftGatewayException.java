package com.shitulelv.aicollab.planning.draft;

/**
 * Planning draft 网关调用失败。
 *
 * <p>{@code message} 只允许是固定的本地描述（例如 “planning draft worker 读取超时”），
 * <b>绝不允许</b>携带 goal、快照内容、服务令牌或上游响应原文——异常会进日志，
 * 也可能被上层拼接进对外错误。</p>
 */
public class PlanningDraftGatewayException extends RuntimeException {

    private final PlanningDraftGatewayErrorCode reason;

    public PlanningDraftGatewayException(PlanningDraftGatewayErrorCode reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PlanningDraftGatewayErrorCode reason() {
        return reason;
    }
}
