package com.shitulelv.aicollab.agent.infrastructure.decide;

/**
 * Agent decide 网关调用失败。
 *
 * <p>{@code message} 只允许是固定的本地描述（例如 "decide worker 读取超时"），
 * <b>绝不允许</b>携带 Prompt、工具参数、快照内容、服务令牌或上游响应原文——
 * 异常会进日志，也可能被上层拼接进对外错误。</p>
 */
public class DecideGatewayException extends RuntimeException {

    private final DecideGatewayErrorCode reason;

    public DecideGatewayException(DecideGatewayErrorCode reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DecideGatewayErrorCode reason() {
        return reason;
    }
}
