package com.shitulelv.aicollab.agent.infrastructure.decide;

import java.util.Locale;

/**
 * Agent decide 网关失败分类（W4 冻结契约 §3.5 的 {@code decide.shadow.failure{reason}} 词表）。
 *
 * <p>取值刻意只有五项，避免高基数标签：超时、不可用、契约不符、HTTP 拒绝、未预期。
 * 响应正文、Prompt、令牌与快照内容<b>绝不</b>进入任何异常消息。</p>
 */
public enum DecideGatewayErrorCode {

    /** 连接超时或读取超时（客户端 read timeout = deadlineMs + 5s）。 */
    TIMEOUT,
    /** 连接失败、5xx/429 等"端点暂不可用"类故障。 */
    UNAVAILABLE,
    /** 非法 JSON、schemaVersion/decision 不匹配、工具名越界等契约违规。 */
    SCHEMA,
    /** 其它非 2xx 状态。 */
    HTTP,
    /** 客户端自身未预期异常（序列化失败、中断等）。 */
    UNEXPECTED;

    /** 指标标签用小写取值（低基数、稳定）。 */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
