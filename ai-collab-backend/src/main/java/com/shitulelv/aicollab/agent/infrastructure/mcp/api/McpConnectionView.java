package com.shitulelv.aicollab.agent.infrastructure.mcp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpConnection;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAuthType;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpTransport;
import com.shitulelv.aicollab.common.api.JsonApiValue;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record McpConnectionView(
        UUID id, String code, String name, McpTransport transport, String endpoint,
        McpAuthType authType, boolean credentialConfigured, int timeoutMs, int maxResultBytes,
        List<String> toolAllowlist, List<String> resourceAllowlist,
        List<DiscoveredToolView> discoveredTools, List<DiscoveredResourceView> discoveredResources,
        String schemaHash, String confirmedSchemaHash,
        boolean schemaConfirmed, boolean enabled, String lastHealthStatus,
        String lastHealthMessage, OffsetDateTime lastHealthAt, int version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static McpConnectionView from(McpConnection value) {
        return new McpConnectionView(value.id(), value.code(), value.name(), value.transport(),
                value.endpoint(), value.authType(), value.credentialCiphertext() != null,
                value.timeoutMs(), value.maxResultBytes(), strings(value.toolAllowlistJson()),
                strings(value.resourceAllowlistJson()), tools(value.discoveredToolsJson()),
                resources(value.discoveredResourcesJson()), value.schemaHash(), value.confirmedSchemaHash(),
                value.schemaHash() != null && value.schemaHash().equals(value.confirmedSchemaHash()),
                value.enabled(), value.lastHealthStatus(), value.lastHealthMessage(),
                value.lastHealthAt(), value.version(), value.createdAt(), value.updatedAt());
    }

    private static List<String> strings(String source) {
        JsonNode root = array(source);
        List<String> result = new ArrayList<>();
        for (JsonNode value : root) {
            if (!value.isTextual()) throw invalidData();
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static List<DiscoveredToolView> tools(String source) {
        List<DiscoveredToolView> result = new ArrayList<>();
        for (JsonNode value : array(source)) {
            if (!value.isObject() || !value.path("name").isTextual()) throw invalidData();
            result.add(new DiscoveredToolView(
                    value.path("name").textValue(), nullableText(value.get("description")),
                    JsonApiValue.from(value.get("inputSchema")), JsonApiValue.from(value.get("outputSchema")),
                    JsonApiValue.from(value.get("annotations"))));
        }
        return List.copyOf(result);
    }

    private static List<DiscoveredResourceView> resources(String source) {
        List<DiscoveredResourceView> result = new ArrayList<>();
        for (JsonNode value : array(source)) {
            if (!value.isObject() || !value.path("uri").isTextual()) throw invalidData();
            result.add(new DiscoveredResourceView(
                    value.path("uri").textValue(), nullableText(value.get("name")),
                    nullableText(value.get("description")), nullableText(value.get("mimeType"))));
        }
        return List.copyOf(result);
    }

    private static JsonNode array(String source) {
        try {
            JsonNode root = JSON.readTree(source);
            if (root == null || !root.isArray()) throw invalidData();
            return root;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidData();
        }
    }

    private static String nullableText(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (!value.isTextual()) throw invalidData();
        return value.textValue();
    }

    private static BusinessException invalidData() {
        return new BusinessException(ErrorCode.AGENT_MCP_DATA_INVALID);
    }

    public record DiscoveredToolView(
            String name, String description, Object inputSchema, Object outputSchema, Object annotations) {}

    public record DiscoveredResourceView(
            String uri, String name, String description, String mimeType) {}
}
