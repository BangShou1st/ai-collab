package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code agent.mcp.gateway.*} 配置。Gateway 默认关闭，静态测试绑定默认全空。
 */
@ConfigurationProperties("agent.mcp.gateway")
public record McpGatewayProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String staticTestProjectRef,
        @DefaultValue("") String staticTestRunRef,
        @DefaultValue("") String staticTestCatalogVersion) {

    public McpGatewayProperties {
        staticTestProjectRef = clean(staticTestProjectRef);
        staticTestRunRef = clean(staticTestRunRef);
        staticTestCatalogVersion = clean(staticTestCatalogVersion);
    }

    public boolean hasStaticTestBinding() {
        return !staticTestProjectRef.isEmpty() || !staticTestRunRef.isEmpty()
                || !staticTestCatalogVersion.isEmpty();
    }

    public boolean completeStaticTestBinding() {
        return !staticTestProjectRef.isEmpty() && !staticTestRunRef.isEmpty()
                && !staticTestCatalogVersion.isEmpty();
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
