package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpConnectionView;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpAdministrationServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final UUID projectId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();

    @Test
    void discoverySchemaChangeDisablesConnectionAndWritesAudit() throws Exception {
        McpRepository repository = mock(McpRepository.class);
        McpClientFactory clients = mock(McpClientFactory.class);
        McpConnectionManager manager = mock(McpConnectionManager.class);
        AuditService audit = mock(AuditService.class);
        McpConnection existing = connection("a".repeat(64), true);
        McpConnection disabled = connection("b".repeat(64), false);
        when(repository.find(connectionId)).thenReturn(Optional.of(existing));
        McpClientFacade client = mock(McpClientFacade.class);
        when(clients.create(existing)).thenReturn(client);
        when(client.initialize()).thenReturn(new McpClientFacade.ServerInfo("fixture", "1"));
        ObjectNode schema = json.createObjectNode().put("type", "object");
        when(client.listTools()).thenReturn(List.of(new McpClientFacade.DiscoveredTool(
                "get_file", "Read file", schema, null,
                json.createObjectNode().put("readOnlyHint", true))));
        when(client.listResources()).thenReturn(List.of());
        when(repository.discovered(eq(connectionId), anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(disabled);

        McpAdministrationService service = new McpAdministrationService(
                mock(ProjectAccessGuard.class), repository, clients, manager,
                mock(ModelSecretCipher.class), mock(McpEndpointPolicy.class), json, audit);

        McpConnectionView result = service.discover(projectId, connectionId, adminId);

        assertThat(result.enabled()).isFalse();
        verify(manager).invalidate(projectId, connectionId);
        verify(audit).write(eq(projectId), eq(adminId), eq("AGENT_MCP_SCHEMA_CHANGED"),
                eq("AGENT_MCP"), eq(connectionId), anyMap());
    }

    private McpConnection connection(String confirmedHash, boolean enabled) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            return new McpConnection(connectionId, projectId, "local_readonly", "Local",
                    McpTransport.STREAMABLE_HTTP, "https://127.0.0.1:18443/mcp", null,
                    McpAuthType.NONE, null, null, 10_000, 65_536,
                    json.writeValueAsString(List.of("get_file")), "[]",
                    json.writeValueAsString(List.of()), "[]",
                    "a".repeat(64), confirmedHash, enabled, "HEALTHY", null, now,
                    adminId, 1, now, now);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
