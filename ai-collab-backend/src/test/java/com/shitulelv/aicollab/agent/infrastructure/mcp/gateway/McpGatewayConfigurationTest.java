package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpConnectionManager;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpRepository;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolProvider;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAgentToolProvider;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpGatewayConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(McpGatewayConfiguration.class, McpGatewayController.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(McpRepository.class, () -> mock(McpRepository.class))
            .withBean(AgentRepository.class, () -> mock(AgentRepository.class))
            .withBean(ProjectAccessGuard.class, () -> mock(ProjectAccessGuard.class))
            .withBean(AuditService.class, () -> mock(AuditService.class))
            .withBean(McpConnectionManager.class, () -> mock(McpConnectionManager.class))
            .withBean(McpResultSanitizer.class, () -> new McpResultSanitizer(new ObjectMapper()))
            .withBean(McpAgentToolProvider.class, () -> mock(McpAgentToolProvider.class));

    @Test
    void defaultOffKeepsLegacyProviderAndDoesNotAssembleGatewayEndpoint() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(McpGatewayProperties.class).enabled()).isFalse();
            assertThat(context).doesNotHaveBean(McpGatewayController.class);
            assertThat(context).hasSingleBean(McpAgentToolProvider.class);
            assertThat(context.getBean(McpAgentToolProvider.class)).isInstanceOf(AgentToolProvider.class);
        });
    }

    @Test
    void enabledPropertyAssemblesEndpointWithoutRemovingLegacyProvider() {
        runner.withPropertyValues("agent.mcp.gateway.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpGatewayController.class);
            assertThat(context).hasSingleBean(McpGatewayService.class);
            assertThat(context).hasSingleBean(McpAgentToolProvider.class);
        });
    }

    @Test
    void fullPackageScanStillLeavesEndpointUnassembledByDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("agent.mcp.gateway.enabled", "false")));
            context.scan("com.shitulelv.aicollab.agent.infrastructure.mcp.gateway");
            context.refresh();

            assertThat(context.getBeansOfType(McpGatewayController.class)).isEmpty();
            assertThat(context.getBeansOfType(McpGatewayService.class)).isEmpty();
            assertThat(context.getBean(McpGatewayProperties.class).enabled()).isFalse();
        }
    }

}
