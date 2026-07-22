package com.shitulelv.aicollab.auth.refresh.model;

/**
 * Refresh Token 失效的可审计原因；枚举名称与 V2 数据库检查约束保持一一对应。
 */
public enum RefreshTokenRevokeReason {
    /** 正常轮换后，旧 Token 不可再次使用。 */
    ROTATED,
    /** 用户主动退出当前设备。 */
    LOGOUT,
    /** 已轮换 Token 被再次提交，当前会话必须整体失效。 */
    REUSE_DETECTED,
    /** Token 自身已过期。 */
    EXPIRED,
    /** 所属用户不可用，不能继续签发 Access Token。 */
    USER_UNAVAILABLE
}
