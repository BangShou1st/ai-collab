package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

/**
 * Gateway 内部可稳定映射到冻结错误码的异常。消息由网关固定生成，不透传远端正文。
 */
final class McpGatewayException extends RuntimeException {

    enum Kind {
        UNAUTHORIZED("UNAUTHORIZED", false),
        TOOL_DISABLED("TOOL_DISABLED", false),
        SCHEMA_DRIFT("SCHEMA_DRIFT", false),
        PARAM_INVALID("PARAM_INVALID", false),
        NOT_EXECUTED("NOT_EXECUTED", true),
        DEADLINE_EXCEEDED("DEADLINE_EXCEEDED", false),
        RATE_LIMITED("RATE_LIMITED", true),
        PARTIAL("PARTIAL", false),
        TRUNCATED("TRUNCATED", false),
        TOOL_REVOKED("TOOL_REVOKED", false),
        OUTCOME_UNKNOWN("OUTCOME_UNKNOWN", false),
        INTERNAL("INTERNAL", false);

        private final String wireName;
        private final boolean retryable;

        Kind(String wireName, boolean retryable) {
            this.wireName = wireName;
            this.retryable = retryable;
        }

        String wireName() {
            return wireName;
        }

        boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;
    private final int jsonRpcCode;

    private McpGatewayException(Kind kind, int jsonRpcCode, String message) {
        super(message, null, false, false);
        this.kind = kind;
        this.jsonRpcCode = jsonRpcCode;
    }

    static McpGatewayException business(Kind kind) {
        return new McpGatewayException(kind, -32000, message(kind));
    }

    static McpGatewayException invalidParams() {
        return new McpGatewayException(Kind.PARAM_INVALID, -32602, "请求参数无效");
    }

    static McpGatewayException unknownMethod() {
        return new McpGatewayException(Kind.INTERNAL, -32601, "未知方法");
    }

    Kind kind() {
        return kind;
    }

    int jsonRpcCode() {
        return jsonRpcCode;
    }

    private static String message(Kind kind) {
        return switch (kind) {
            case UNAUTHORIZED -> "身份或项目上下文无效";
            case TOOL_DISABLED -> "工具未启用";
            case SCHEMA_DRIFT -> "工具目录已变化";
            case PARAM_INVALID -> "工具参数无效";
            case NOT_EXECUTED -> "工具未执行";
            case DEADLINE_EXCEEDED -> "工具调用超时";
            case RATE_LIMITED -> "外部服务限流";
            case PARTIAL -> "工具仅返回部分结果";
            case TRUNCATED -> "工具结果已截断";
            case TOOL_REVOKED -> "工具授权已撤销";
            case OUTCOME_UNKNOWN -> "工具执行结果未知";
            case INTERNAL -> "网关内部错误";
        };
    }
}
