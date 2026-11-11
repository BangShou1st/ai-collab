package com.shitulelv.aicollab.internal.ai.token;

/**
 * 静态服务令牌提供者：{@code static} 模式（也是所有测试便捷构造器的回退）。
 *
 * <p>返回值直接来自 {@code rerank.service-token}，语义与 W3 完全一致；未配置时返回
 * {@code ""}，rerank 网关据此不发送 Authorization 头，内部端点由过滤器 fail closed。</p>
 */
public final class StaticInternalTokenProvider implements InternalTokenProvider {

    private final String token;

    public StaticInternalTokenProvider(String token) {
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public String token() {
        return token;
    }
}
