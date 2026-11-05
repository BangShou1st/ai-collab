package com.shitulelv.aicollab.auth.dto;

/** 邮箱验证请求的结果；不包含邮箱地址、令牌或其他敏感内容。 */
public record AccountEmailDispatchResult(Status status, long retryAfterSeconds) {
    public static AccountEmailDispatchResult queued(long retryAfterSeconds) {
        return new AccountEmailDispatchResult(Status.QUEUED, retryAfterSeconds);
    }

    public static AccountEmailDispatchResult cooldown(long retryAfterSeconds) {
        return new AccountEmailDispatchResult(Status.COOLDOWN, retryAfterSeconds);
    }

    public static AccountEmailDispatchResult unavailable() {
        return new AccountEmailDispatchResult(Status.UNAVAILABLE, 0);
    }

    public static AccountEmailDispatchResult notRequested() {
        return new AccountEmailDispatchResult(Status.NOT_REQUESTED, 0);
    }

    public enum Status {
        QUEUED,
        COOLDOWN,
        UNAVAILABLE,
        NOT_REQUESTED
    }
}
