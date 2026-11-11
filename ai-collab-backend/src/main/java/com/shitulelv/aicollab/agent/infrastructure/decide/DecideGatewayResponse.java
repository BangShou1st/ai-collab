package com.shitulelv.aicollab.agent.infrastructure.decide;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Python {@code POST /v1/agent/decide} 的解析结果（W4 冻结契约 §2）。
 *
 * <p>{@code usage} 缺失时 {@code source=UNKNOWN}、token 为 null，不用 0 冒充"已知"；
 * {@code latencyMs} 可空。</p>
 */
public record DecideGatewayResponse(
        int schemaVersion,
        DecideDecision decision,
        ToolCall toolCall,
        Usage usage,
        String promptVersion,
        String modelAlias,
        Long latencyMs) {

    public DecideGatewayResponse {
        usage = usage == null ? Usage.unknown() : usage;
    }

    /** CALL_TOOL 的工具调用；其它决策必须为 null。 */
    public record ToolCall(String name, JsonNode arguments) {
    }

    /** usage 缺失 → UNKNOWN/null。 */
    public record Usage(Integer inputTokens, Integer outputTokens, String source) {
        public static Usage unknown() {
            return new Usage(null, null, "UNKNOWN");
        }
    }
}
