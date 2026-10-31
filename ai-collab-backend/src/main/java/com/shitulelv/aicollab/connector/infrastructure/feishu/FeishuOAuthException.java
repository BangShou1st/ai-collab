package com.shitulelv.aicollab.connector.infrastructure.feishu;

/** Does not retain provider responses, request bodies or credentials. */
public final class FeishuOAuthException extends RuntimeException {
    public enum Kind { REAUTH_REQUIRED, REVOKED, TEMPORARY, OUTCOME_UNKNOWN, CONFIGURATION }

    private final Kind kind;

    public FeishuOAuthException(Kind kind) {
        super(switch (kind) {
            case REAUTH_REQUIRED -> "飞书授权需要重新确认";
            case REVOKED -> "飞书授权已撤销";
            case TEMPORARY -> "飞书授权服务暂时不可用";
            case OUTCOME_UNKNOWN -> "飞书令牌轮换结果未知，请重新授权";
            case CONFIGURATION -> "飞书授权配置或响应无效";
        });
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
