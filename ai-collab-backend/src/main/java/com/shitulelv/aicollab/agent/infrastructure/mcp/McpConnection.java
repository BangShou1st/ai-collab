package com.shitulelv.aicollab.agent.infrastructure.mcp;

import java.time.OffsetDateTime;
import java.util.UUID;

public record McpConnection(
        UUID id, UUID projectId, String code, String name, McpTransport transport, String endpoint,
        String stdioCommandJson, McpAuthType authType, String credentialCiphertext,
        Integer credentialKeyVersion, int timeoutMs, int maxResultBytes,
        String toolAllowlistJson, String resourceAllowlistJson,
        String discoveredToolsJson, String discoveredResourcesJson,
        String schemaHash, String confirmedSchemaHash, boolean enabled,
        String lastHealthStatus, String lastHealthMessage, OffsetDateTime lastHealthAt,
        UUID createdBy, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
