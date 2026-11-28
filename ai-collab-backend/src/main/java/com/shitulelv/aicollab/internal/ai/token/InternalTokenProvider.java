package com.shitulelv.aicollab.internal.ai.token;

import java.util.UUID;

/**
 * Java → Python 入站调用的内部服务令牌提供者。
 *
 * <p>每次调用都重新签发 Ed25519 短令牌（{@code jti/iat} 每次不同）。调用方在<b>每次 HTTP 尝试</b>
 * 前取一次令牌，保证短令牌不会因重试而显著变旧。</p>
 *
 * <p>P1 起（契约 §1、R1 §9.6）入站令牌必须携带调用身份 {@code uid/pid}、对应 {@code purp}，以及
 * {@code credentials:lease usage:report}，Python 原样转发给 Java 的租借与用量接口。
 * 不再提供"无身份"的服务令牌。</p>
 */
@FunctionalInterface
public interface InternalTokenProvider {

    /**
     * {@code /v1/rag/plan} 入站令牌（不含 {@code Bearer } 前缀）。
     *
     * @param userId    调用用户（必填）
     * @param projectId 调用项目（必填）
     * @return 令牌；生产装配下恒为非空
     */
    String queryPlanToken(UUID userId, UUID projectId);

    /**
     * {@code POST /v1/knowledge/search} 入站令牌（P2 契约 §4.1）。默认实现 fail closed：
     * 只有生产的 Ed25519 实现会签发；测试桩未覆盖时调用方拿不到令牌、不发请求。
     */
    default String knowledgeSearchToken(UUID userId, UUID projectId) {
        throw new UnsupportedOperationException("knowledge search token not supported");
    }

    /**
     * {@code POST /v1/knowledge/qa:stream} 入站令牌（P3 契约 §3.1，Q14=a+c）。
     * 默认实现 fail closed，与 {@link #knowledgeSearchToken(UUID, UUID)} 同口径。
     */
    default String knowledgeQaToken(UUID userId, UUID projectId) {
        throw new UnsupportedOperationException("knowledge qa token not supported");
    }

    /** {@code /v1/index/generations/**} 管理令牌（P2 契约 §4.6，scope {@code index:admin}）。默认 fail closed。 */
    default String indexAdminToken() {
        throw new UnsupportedOperationException("index admin token not supported");
    }
}
