package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpConnectionRequest;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpConnectionView;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpBindingRequest;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpBindingView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "agent.mcp.allowed-hosts=example.com",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "security.jwt.access-token-minutes=30"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class McpAdministrationPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    private static final String MINIO_ACCESS_KEY = "mcp-access";
    private static final String MINIO_SECRET_KEY = "mcp-secret-key";
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static final UUID ADMIN = UUID.fromString("73000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT = UUID.fromString("73000000-0000-0000-0000-000000000002");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("storage.minio.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("storage.minio.secret-key", () -> MINIO_SECRET_KEY);
    }

    @Autowired McpAdministrationService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean McpClientFactory clients;

    @BeforeEach
    void seedAdmin() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name,system_admin)
                VALUES (?,'mcp-admin','hash','MCP Admin',true)
                """, ADMIN);
    }

    @Test
    void createEditKeepCredentialRotateDiscoverAndEnableFormOneContractSafeFlow() throws Exception {
        McpConnectionView created = service.create(request("initial-secret", 0, List.of("get_file")), ADMIN);
        String firstCiphertext = ciphertext(created.id());
        assertThat(created.toolAllowlist()).containsExactly("get_file");
        assertThat(firstCiphertext).isNotEqualTo("initial-secret");

        McpConnectionView edited = service.update(
                created.id(), request(null, created.version(), List.of("get_file", "list_issues")), ADMIN);
        assertThat(ciphertext(created.id())).isEqualTo(firstCiphertext);
        assertThat(edited.toolAllowlist()).containsExactly("get_file", "list_issues");

        McpConnectionView rotated = service.update(
                created.id(), request("rotated-secret", edited.version(), edited.toolAllowlist()), ADMIN);
        assertThat(ciphertext(created.id())).isNotEqualTo(firstCiphertext);

        ObjectMapper json = new ObjectMapper();
        McpClientFacade client = org.mockito.Mockito.mock(McpClientFacade.class);
        when(clients.create(any())).thenReturn(client);
        when(client.initialize()).thenReturn(new McpClientFacade.ServerInfo("fixture", "1"));
        when(client.listTools()).thenReturn(List.of(new McpClientFacade.DiscoveredTool(
                "get_file", "Read file", json.createObjectNode(), null,
                json.createObjectNode().put("readOnlyHint", true))));
        when(client.listResources()).thenReturn(List.of(new McpClientFacade.DiscoveredResource(
                "repo://main", "main", "Repository", "text/plain")));

        McpConnectionView discovered = service.discover(created.id(), ADMIN);
        assertThat(discovered.discoveredTools()).extracting(McpConnectionView.DiscoveredToolView::name)
                .containsExactly("get_file");
        assertThat(discovered.discoveredResources()).extracting(McpConnectionView.DiscoveredResourceView::uri)
                .containsExactly("repo://main");

        McpConnectionView enabled = service.setEnabled(created.id(), discovered.version(), true, ADMIN);
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.schemaConfirmed()).isTrue();
        assertThat(enabled.confirmedSchemaHash()).isEqualTo(enabled.schemaHash());

        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'MCP binding',?,?)",
                PROJECT, ADMIN, ADMIN);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                PROJECT, ADMIN);
        McpBindingView binding = service.bind(PROJECT, enabled.id(), new McpBindingRequest(
                true, List.of("get_file"), List.of("repo://main"),
                Map.of("repository", "main"), 0), ADMIN);

        assertThat(binding.allowedTools()).containsExactly("get_file");
        assertThat(binding.configuration()).containsEntry("repository", "main");
        String bindingResponse = new tools.jackson.databind.ObjectMapper().writeValueAsString(binding);
        assertThat(new ObjectMapper().readTree(bindingResponse)
                .path("configuration").path("repository").asText()).isEqualTo("main");
    }

    private McpConnectionRequest request(String credential, int version, List<String> tools) {
        return new McpConnectionRequest(
                "github-readonly", "GitHub readonly", McpTransport.STREAMABLE_HTTP,
                "https://example.com/mcp", null, McpAuthType.BEARER, credential,
                10_000, 65_536, tools, List.of("repo://main"), version);
    }

    private String ciphertext(UUID connectionId) {
        return jdbc.queryForObject(
                "SELECT credential_ciphertext FROM agent_mcp_connection WHERE id=?",
                String.class, connectionId);
    }
}
