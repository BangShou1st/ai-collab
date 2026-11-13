package com.shitulelv.aicollab.agent.infrastructure.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpLoopbackTestGuardTest {

    @Test
    void testLoopbackIsRejectedInProductionAndRequiresActiveLocalOrTestProfile() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> new McpLoopbackTestGuard(true, local).afterPropertiesSet())
                .doesNotThrowAnyException();

        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        assertThatCode(() -> new McpLoopbackTestGuard(true, test).afterPropertiesSet())
                .doesNotThrowAnyException();

        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertThatThrownBy(() -> new McpLoopbackTestGuard(true, production).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new McpLoopbackTestGuard(false, production).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
