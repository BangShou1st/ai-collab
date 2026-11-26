package com.shitulelv.aicollab.internal.ai.credential;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;

import java.util.Optional;

/**
 * 凭据租借用途（契约 §2.1）及其到现有路由用途 {@link ModelPurpose} 的映射。
 *
 * <p>映射与 P1 之前已删除的同步模型代理端点的实际调用完全一致：
 * query plan 调用不传 purpose，控制器缺省为 {@code KNOWLEDGE_CHAT}；记忆抽取显式传 {@code AGENT}。</p>
 *
 * <p>{@code EMBEDDING}、{@code RERANK} 不走聊天路由（{@link #routingPurpose()} 为空），由
 * {@link CredentialLeaseService} 分别路由到系统 Embedding 配置与 RERANK 能力绑定
 * （P2 契约 §12.2 P1-R4、§12.3 R1-3/R1-4）。</p>
 */
public enum LeasePurpose {
    KNOWLEDGE_CHAT(ModelPurpose.KNOWLEDGE_CHAT),
    AGENT(ModelPurpose.AGENT),
    PLANNING(ModelPurpose.PLANNING),
    QUERY_PLAN(ModelPurpose.KNOWLEDGE_CHAT),
    MEMORY_EXTRACTION(ModelPurpose.AGENT),
    EMBEDDING(null),
    RERANK(null);

    private final ModelPurpose routingPurpose;

    LeasePurpose(ModelPurpose routingPurpose) {
        this.routingPurpose = routingPurpose;
    }

    /** 聊天路由用途；为空表示该用途不走聊天模型路由。 */
    public Optional<ModelPurpose> routingPurpose() {
        return Optional.ofNullable(routingPurpose);
    }

    /** 严格解析：只接受精确的枚举名，非法返回空。 */
    public static Optional<LeasePurpose> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (LeasePurpose value : values()) {
            if (value.name().equals(raw)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
