package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ModelToolDefinition(String name, String description, JsonNode inputSchema) {
}
