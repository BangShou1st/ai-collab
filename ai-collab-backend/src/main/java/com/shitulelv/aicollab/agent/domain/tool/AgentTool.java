package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgentTool {
    String name();
    boolean writesBusinessData();
    AgentToolResult execute(AgentToolContext context, JsonNode arguments);

    default AgentToolDefinition definition() {
        return AgentToolDefinition.openObject(
                name(), "调用项目工具 " + name(), writesBusinessData());
    }
}
