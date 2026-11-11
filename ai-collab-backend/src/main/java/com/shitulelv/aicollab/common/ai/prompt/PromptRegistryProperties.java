package com.shitulelv.aicollab.common.ai.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * PromptRegistry 配置（设计 §7.2 / 交接文档 §9）。
 *
 * <p>默认值刻意让"零外呼"成为安全默认：{@code enabled=false} 时只读 classpath
 * fallback 包，行为与迁移前逐字节一致；只有显式开启才访问内部 ai-worker。</p>
 *
 * <p>防御性归一：timeout 非正数会让 JDK HttpClient 直接抛 {@link IllegalArgumentException}
 * 从而阻断启动，因此这里回退到默认值（配置错误不应阻止应用启动）；{@code refresh-interval}
 * 允许 0 或负数，语义是"只做启动时的一次尝试，不周期刷新"，不做归一。</p>
 */
@ConfigurationProperties("prompt.registry")
public record PromptRegistryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://ai-worker-api:9100") String baseUrl,
        @DefaultValue("PT5M") Duration refreshInterval,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout) {

    private static final String DEFAULT_BASE_URL = "http://ai-worker-api:9100";
    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    public PromptRegistryProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        } else {
            baseUrl = baseUrl.trim();
        }
        if (refreshInterval == null) {
            refreshInterval = DEFAULT_REFRESH_INTERVAL;
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
    }

    /** 默认值（本地 fallback 路径），供不依赖 Spring Binder 的代码与测试使用。 */
    public static PromptRegistryProperties defaults() {
        return new PromptRegistryProperties(false, DEFAULT_BASE_URL, DEFAULT_REFRESH_INTERVAL,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }
}
