package com.shitulelv.aicollab.internal.ai.token;

import java.util.UUID;

/**
 * 内部服务令牌提供者（冻结契约 §2.2/§2.4）。
 *
 * <p>每次调用都重新签发 Ed25519 短令牌（确定性签名，但 {@code jti/iat} 每次不同）。调用方在
 * <b>每次 HTTP 尝试</b>前取一次令牌，保证短令牌不会因重试而显著变旧。</p>
 */
public interface InternalTokenProvider {

    /**
     * 当前可用的 Bearer 令牌值（不含 {@code Bearer } 前缀）。
     *
     * @return 令牌（Ed25519 私钥为启动必填，生产装配下恒为非空）
     */
    String token();

    /**
     * 携带调用用户与可选项目身份签发 Bearer 令牌。接口默认实现忽略调用者身份、直接返回
     * {@link #token()}；生产实现 {@link Ed25519InternalTokenProvider} 覆盖本方法，把
     * caller/project 写入 claims。
     */
    default String tokenForCaller(UUID callerUserId, UUID projectId) {
        return token();
    }
}
