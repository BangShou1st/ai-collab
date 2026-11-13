package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 静态项目绑定只能用于 local/test；生产 profile 一旦配置即启动失败。
 */
final class McpGatewayStaticTestGuard implements InitializingBean {

    private final McpGatewayProperties properties;
    private final Environment environment;

    McpGatewayStaticTestGuard(McpGatewayProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.hasStaticTestBinding()) {
            return;
        }
        if (!properties.completeStaticTestBinding()
                || !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException(
                    "agent.mcp.gateway 静态测试绑定只允许在 local/test profile 且三项配置完整时启用");
        }
    }
}
