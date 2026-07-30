package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record AgentToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        boolean writesBusinessData) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public AgentToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("工具说明不能为空");
        }
        if (inputSchema == null || !inputSchema.isObject()) {
            throw new IllegalArgumentException("工具 Schema 必须是 JSON 对象");
        }
        name = name.strip();
        description = description.strip();
        inputSchema = inputSchema.deepCopy();
    }

    public static AgentToolDefinition openObject(
            String name, String description, boolean writesBusinessData) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return new AgentToolDefinition(name, description, schema, writesBusinessData);
    }

    public static AgentToolDefinition fromJson(
            String name, String description, String schema, boolean writesBusinessData) {
        try {
            return new AgentToolDefinition(
                    name, description, JSON.readTree(schema), writesBusinessData);
        } catch (Exception exception) {
            throw new IllegalArgumentException("工具 Schema 不是合法 JSON", exception);
        }
    }
}
