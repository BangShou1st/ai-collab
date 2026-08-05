package com.shitulelv.aicollab.agent.infrastructure.mcp.api;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record McpBindingRequest(
        boolean enabled, @NotNull List<String> allowedTools,
        @NotNull List<String> allowedResources, Map<String, Object> configuration, int version) {
}
