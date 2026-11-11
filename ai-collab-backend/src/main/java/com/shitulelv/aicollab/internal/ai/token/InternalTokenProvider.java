package com.shitulelv.aicollab.internal.ai.token;

/**
 * 内部服务令牌提供者（冻结契约 §2.2/§2.4）。
 *
 * <p>{@code static} 模式返回配置的固定令牌；{@code ed25519}/{@code dual} 模式每次调用都
 * 重新签名（Ed25519 确定性签名，但 {@code jti/iat} 每次不同）。调用方（rerank 网关）在
 * <b>每次 HTTP 尝试</b>前取一次令牌，保证短令牌不会因重试而显著变旧。</p>
 */
public interface InternalTokenProvider {

    /**
     * 当前可用的 Bearer 令牌值（不含 {@code Bearer } 前缀）。
     *
     * @return 令牌；未配置时为 {@code ""}（调用方不得发送 Authorization 头）
     */
    String token();
}
