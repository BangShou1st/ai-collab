package com.shitulelv.aicollab.p2b1probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAgentToolProvider;
import com.shitulelv.aicollab.agent.infrastructure.mcp.gateway.McpGatewayProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("p2b1-probe")
public class P2B1ProbeConfiguration {

    @Bean
    P2B1RuntimeProbe p2b1RuntimeProbe(
            ApplicationContext context,
            JdbcTemplate jdbc,
            ObjectMapper json,
            McpAgentToolProvider provider,
            McpGatewayProperties gatewayProperties) {
        return new P2B1RuntimeProbe(context, jdbc, json, provider, gatewayProperties);
    }
}
