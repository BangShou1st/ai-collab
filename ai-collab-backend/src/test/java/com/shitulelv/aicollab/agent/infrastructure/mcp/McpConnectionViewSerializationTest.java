package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpConnectionView;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpBindingRequest;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpBindingView;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McpConnectionViewSerializationTest {
    @Test
    void springResponseSerializerWritesAllowlistAndDiscoveryFieldsAsJsonArrays() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-05T10:00:00+08:00");
        McpConnection connection = new McpConnection(
                UUID.randomUUID(), "github", "GitHub", McpTransport.STREAMABLE_HTTP,
                "https://mcp.example.com/mcp", null, McpAuthType.BEARER, "encrypted", 1,
                10_000, 65_536, "[\"get_file\"]", "[\"repo://main\"]",
                "[{\"name\":\"get_file\",\"description\":\"read\",\"inputSchema\":{},"
                        + "\"outputSchema\":null,\"annotations\":{\"readOnlyHint\":true}}]",
                "[{\"uri\":\"repo://main\",\"name\":\"main\",\"description\":\"repo\","
                        + "\"mimeType\":\"text/plain\"}]",
                "hash", "hash", false, "HEALTHY", null, now,
                UUID.randomUUID(), 2, now, now);

        String responseJson = new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(McpConnectionView.from(connection));
        JsonNode root = new ObjectMapper().readTree(responseJson);

        assertThat(root.path("toolAllowlist").isArray()).isTrue();
        assertThat(root.path("resourceAllowlist").isArray()).isTrue();
        assertThat(root.path("discoveredTools").isArray()).isTrue();
        assertThat(root.path("discoveredResources").isArray()).isTrue();
        assertThat(root.path("toolAllowlist").get(0).asText()).isEqualTo("get_file");
        assertThat(root.path("discoveredTools").get(0).path("name").asText()).isEqualTo("get_file");
    }

    @Test
    void springRequestAndResponseMapperHandlesBindingConfigurationAsOrdinaryJson() throws Exception {
        tools.jackson.databind.ObjectMapper springJson = new tools.jackson.databind.ObjectMapper();
        McpBindingRequest request = springJson.readValue("""
                {"enabled":true,"allowedTools":["get_file"],"allowedResources":[],
                 "configuration":{"repository":"main"},"version":0}
                """, McpBindingRequest.class);

        OffsetDateTime now = OffsetDateTime.parse("2026-08-05T10:00:00+08:00");
        McpBindingView view = new McpBindingView(
                UUID.randomUUID(), UUID.randomUUID(), "github", "GitHub", true, true,
                List.of("get_file"), List.of(), request.configuration(), 0, now, now);
        JsonNode response = new ObjectMapper().readTree(springJson.writeValueAsString(view));

        assertThat(response.path("configuration").path("repository").asText()).isEqualTo("main");
    }
}
