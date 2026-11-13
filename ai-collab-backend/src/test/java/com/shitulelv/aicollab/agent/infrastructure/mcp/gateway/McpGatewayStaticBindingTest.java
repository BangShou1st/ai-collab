package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpGatewayStaticBindingTest {

    private static final String CATALOG = "sha256:" + "a".repeat(64);

    @Test
    void staticIdentityIsEmptyByDefaultAndFailsClosed() {
        assertThatThrownBy(() -> McpGatewayIdentity.resolve(Map.of(),
                new McpGatewayProperties(false, "", "", "")))
                .isInstanceOf(McpGatewayIdentity.McpGatewayHttpAuthException.class);

        assertThatThrownBy(() -> McpGatewayIdentity.resolve(Map.of(),
                new McpGatewayProperties(true, UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), "")))
                .isInstanceOf(McpGatewayIdentity.McpGatewayHttpAuthException.class);
    }

    @Test
    void completeStaticBindingIsExplicitlyMarkedTestOnly() {
        UUID project = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        McpGatewayIdentity identity = McpGatewayIdentity.resolve(Map.of(),
                new McpGatewayProperties(true, project.toString(), run.toString(), CATALOG));

        assertThat(identity.staticTest()).isTrue();
        assertThat(identity.projectRef()).isEqualTo(project);
        assertThat(identity.runRef()).isEqualTo(run);
        assertThat(identity.canCall()).isTrue();
    }

    @Test
    void staticBindingGuardAllowsOnlyCompleteLocalOrTestConfiguration() {
        McpGatewayProperties complete = new McpGatewayProperties(true,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), CATALOG);
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> new McpGatewayStaticTestGuard(complete, local).afterPropertiesSet())
                .doesNotThrowAnyException();

        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertThatThrownBy(() -> new McpGatewayStaticTestGuard(complete, production).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);

        McpGatewayProperties partial = new McpGatewayProperties(true,
                UUID.randomUUID().toString(), "", CATALOG);
        assertThatThrownBy(() -> new McpGatewayStaticTestGuard(partial, local).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }
}
