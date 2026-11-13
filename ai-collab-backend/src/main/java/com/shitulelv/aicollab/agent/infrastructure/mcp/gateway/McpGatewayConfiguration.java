package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpConnectionManager;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpRepository;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Gateway 垂直切片装配。默认关闭时只保留配置属性，不注册任何 Gateway Controller/Service。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpGatewayProperties.class)
public class McpGatewayConfiguration {

    @Bean
    McpGatewayStaticTestGuard mcpGatewayStaticTestGuard(
            McpGatewayProperties properties, Environment environment) {
        return new McpGatewayStaticTestGuard(properties, environment);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.mcp.gateway.enabled", havingValue = "true")
    McpSchemaSanitizer mcpSchemaSanitizer(ObjectMapper json) {
        return new McpSchemaSanitizer(json);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.mcp.gateway.enabled", havingValue = "true")
    McpGatewayCatalogService mcpGatewayCatalogService(McpRepository repository, ObjectMapper json,
            McpSchemaSanitizer sanitizer) {
        return new McpGatewayCatalogService(repository, json, sanitizer);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.mcp.gateway.enabled", havingValue = "true")
    McpGatewayAuthorizer mcpGatewayAuthorizer(AgentRepository runs, ProjectAccessGuard access) {
        return new DatabaseMcpGatewayAuthorizer(runs, access);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.mcp.gateway.enabled", havingValue = "true")
    McpGatewayAuditor mcpGatewayAuditor(AuditService audit) {
        return new McpGatewayAuditor(audit);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.mcp.gateway.enabled", havingValue = "true")
    McpGatewayService mcpGatewayService(McpGatewayProperties properties,
            McpGatewayCatalogService catalogService, McpGatewayAuthorizer authorizer,
            McpConnectionManager clients, McpResultSanitizer resultSanitizer,
            McpGatewayAuditor auditor, ObjectMapper json) {
        return new McpGatewayService(properties, catalogService, authorizer, clients,
                resultSanitizer, auditor, json);
    }

}
