package com.shitulelv.aicollab.internal.ai.credential;

/**
 * 凭据租借失败：{@link Kind#FORBIDDEN} → 403 {@code LEASE_FORBIDDEN}（不区分原因），
 * {@link Kind#UNAVAILABLE} → 503 {@code AI_PROVIDER_UNAVAILABLE}。消息只含固定原因标签，不含任何凭据。
 */
public class CredentialLeaseException extends RuntimeException {

    public enum Kind { FORBIDDEN, UNAVAILABLE }

    private final Kind kind;
    private final String reason;

    private CredentialLeaseException(Kind kind, String reason) {
        super(reason, null, false, false);
        this.kind = kind;
        this.reason = reason;
    }

    public static CredentialLeaseException forbidden(String reason) {
        return new CredentialLeaseException(Kind.FORBIDDEN, reason);
    }

    public static CredentialLeaseException unavailable(String reason) {
        return new CredentialLeaseException(Kind.UNAVAILABLE, reason);
    }

    public Kind kind() {
        return kind;
    }

    /** 仅用于日志的固定原因标签。 */
    public String reason() {
        return reason;
    }
}
