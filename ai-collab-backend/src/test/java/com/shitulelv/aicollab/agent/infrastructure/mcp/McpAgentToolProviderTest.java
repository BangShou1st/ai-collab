package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class McpAgentToolProviderTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exposesOnlyToolsExplicitlyConfirmedReadOnlyByBothServerAndAdministrators() {
        Fixture fixture = fixture("""
                [
                  {"name":"read_issue","annotations":{"readOnlyHint":true},"inputSchema":{"type":"object"}},
                  {"name":"close_issue","annotations":{"readOnlyHint":false},"inputSchema":{"type":"object"}},
                  {"name":"unclassified","inputSchema":{"type":"object"}}
                ]
                """, List.of("read_issue", "close_issue", "unclassified"));

        List<AgentTool> tools = fixture.provider.tools(fixture.context);

        assertThat(tools).extracting(AgentTool::name).containsExactly("mcp.github.read_issue");
        assertThat(tools.getFirst().writesBusinessData()).isFalse();
    }

    @Test
    void executionFailsClosedWhenConnectionWasDisabledAfterToolExposure() {
        Fixture fixture = fixture("""
                [{"name":"read_issue","annotations":{"readOnlyHint":true},"inputSchema":{"type":"object"}}]
                """, List.of("read_issue"));
        AgentTool tool = fixture.provider.tools(fixture.context).getFirst();
        McpConnection disabled = new McpConnection(
                fixture.connection.id(), fixture.context.projectId(), fixture.connection.code(),
                fixture.connection.name(), fixture.connection.transport(), fixture.connection.endpoint(),
                fixture.connection.stdioCommandJson(), fixture.connection.authType(),
                fixture.connection.credentialCiphertext(), fixture.connection.credentialKeyVersion(),
                fixture.connection.timeoutMs(), fixture.connection.maxResultBytes(),
                fixture.connection.toolAllowlistJson(), fixture.connection.resourceAllowlistJson(),
                fixture.connection.discoveredToolsJson(), fixture.connection.discoveredResourcesJson(),
                fixture.connection.schemaHash(), fixture.connection.confirmedSchemaHash(), false,
                fixture.connection.lastHealthStatus(), fixture.connection.lastHealthMessage(),
                fixture.connection.lastHealthAt(), fixture.connection.createdBy(),
                fixture.connection.version(), fixture.connection.createdAt(), fixture.connection.updatedAt());
        when(fixture.repository.find(fixture.connection.id())).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> tool.execute(toolContext(fixture.context), json.createObjectNode()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_MCP_CONNECTION_DISABLED));
        verifyNoInteractions(fixture.clients);
    }

    private Fixture fixture(String discoveredTools, List<String> allowedTools) {
        McpRepository repository = mock(McpRepository.class);
        McpConnectionManager clients = mock(McpConnectionManager.class);
        AgentExecutionContext context = context();
        McpConnection connection = connection(context.projectId(), discoveredTools, allowedTools);
        when(repository.listByProject(context.projectId())).thenReturn(List.of(connection));
        when(repository.find(connection.id())).thenReturn(Optional.of(connection));
        McpAgentToolProvider provider = new McpAgentToolProvider(
                repository, clients, new McpResultSanitizer(json), json);
        return new Fixture(provider, repository, clients, connection, context);
    }

    private McpConnection connection(UUID projectId, String tools, List<String> allowedTools) {
        OffsetDateTime now = OffsetDateTime.now();
        String allowlist;
        try { allowlist = json.writeValueAsString(allowedTools); }
        catch (Exception error) { throw new AssertionError(error); }
        return new McpConnection(
                UUID.randomUUID(), projectId, "github", "GitHub", McpTransport.STREAMABLE_HTTP,
                "https://example.com/mcp", null, McpAuthType.NONE, null, null,
                5_000, 65_536, allowlist, "[]", tools, "[]",
                "schema-v1", "schema-v1", true, "HEALTHY", null, now,
                UUID.randomUUID(), 1, now, now);
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MEMBER", false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 0, List.of());
    }

    private AgentToolContext toolContext(AgentExecutionContext context) {
        return new AgentToolContext(context.runId(), context.projectId(), context.requesterId(),
                context.projectRole(), context.scheduled(), context.depth());
    }

    private record Fixture(McpAgentToolProvider provider, McpRepository repository,
                           McpConnectionManager clients, McpConnection connection,
                           AgentExecutionContext context) {}
}
