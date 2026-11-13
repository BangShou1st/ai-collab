package com.shitulelv.aicollab.agent.infrastructure.mcp;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 测试 TLS 回环例外只能用于 local/test；生产 profile 显式启用时启动失败。
 */
@Component
final class McpLoopbackTestGuard implements InitializingBean {

    private final boolean enabled;
    private final Environment environment;

    @Autowired
    McpLoopbackTestGuard(
            @Value("${agent.mcp.test-loopback-enabled:false}") boolean enabled,
            Environment environment) {
        this.enabled = enabled;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (enabled && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException(
                    "agent.mcp.test-loopback-enabled 只允许在 local/test profile 启用");
        }
    }
}
