package com.shitulelv.aicollab.internal.ai.token;

import java.util.Objects;
import java.util.UUID;

/**
 * Ed25519 短令牌提供者：每次取令牌都签发一枚新 JWS（exp ≤ 300s）。
 *
 * <p>形状见 {@link InternalTokenIssuer#issueQueryPlanToken(UUID, UUID)}：
 * {@code aud=["ai-worker","backend"]}、{@code scope="plan credentials:lease usage:report"}、
 * {@code purp=["QUERY_PLAN"]}、{@code uid/pid}。</p>
 */
public final class Ed25519InternalTokenProvider implements InternalTokenProvider {

    private final InternalTokenIssuer issuer;

    public Ed25519InternalTokenProvider(InternalTokenIssuer issuer) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
    }

    @Override
    public String queryPlanToken(UUID userId, UUID projectId) {
        return issuer.issueQueryPlanToken(userId, projectId);
    }

    @Override
    public String knowledgeSearchToken(UUID userId, UUID projectId) {
        return issuer.issueKnowledgeSearchToken(userId, projectId);
    }

    @Override
    public String indexAdminToken() {
        return issuer.issueIndexAdminToken();
    }
}
