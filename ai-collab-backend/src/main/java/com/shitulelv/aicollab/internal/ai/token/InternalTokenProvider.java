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
}
