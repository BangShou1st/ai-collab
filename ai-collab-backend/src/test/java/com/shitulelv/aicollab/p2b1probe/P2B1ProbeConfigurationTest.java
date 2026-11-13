package com.shitulelv.aicollab.p2b1probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAgentToolProvider;
import com.shitulelv.aicollab.agent.infrastructure.mcp.gateway.McpGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class P2B1ProbeConfigurationTest {

    @Test
    void probeConfigurationOnlyRegistersWhenTheExplicitTestProfileIsActive() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles("p2b1-probe"))
                .withUserConfiguration(P2B1ProbeConfiguration.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(McpAgentToolProvider.class, () -> mock(McpAgentToolProvider.class))
                .withBean(McpGatewayProperties.class,
                        () -> new McpGatewayProperties(false, "", "", ""))
                .run(context -> {
                    assertThat(context).hasSingleBean(P2B1RuntimeProbe.class);
                });
    }

}
