package com.shitulelv.aicollab.internal.ai.token;

import java.util.Objects;

/**
 * Ed25519 短令牌提供者：每次取令牌都签发一枚新 JWS（TTL 默认 300s）。
 *
 * <p>签发参数固定为冻结契约 §2.1 的同步服务令牌形状：
 * {@code aud=["ai-worker","backend"]}、{@code scope="rerank model-call"}。W5 job token 的
 * 扩展 claims 走 {@link InternalTokenIssuer#issue(java.util.List, java.util.List, java.util.Map)}，
 * 不在本类的请求链路里。</p>
 */
public final class Ed25519InternalTokenProvider implements InternalTokenProvider {

    private final InternalTokenIssuer issuer;

    public Ed25519InternalTokenProvider(InternalTokenIssuer issuer) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
    }

    @Override
    public String token() {
        return issuer.issueServiceToken();
    }
}
