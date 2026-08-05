package com.shitulelv.aicollab.agent.infrastructure.mcp.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record McpBindingView(
        UUID projectId, UUID connectionId, String connectionCode, String connectionName,
        boolean connectionEnabled, boolean enabled, List<String> allowedTools,
        List<String> allowedResources, Map<String, Object> configuration, int version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
