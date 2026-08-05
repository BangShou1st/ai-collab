package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ListMemoriesAgentTool implements AgentTool {
    private final AgentMemoryService memories; private final ObjectMapper json;
    public ListMemoriesAgentTool(AgentMemoryService memories, ObjectMapper json) {
        this.memories = memories; this.json = json;
    }
    @Override public String name() { return "list_project_memories"; }
    @Override public boolean writesBusinessData() { return false; }
    @Override public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(), "读取当前项目最多 10 条有效记忆",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}", false);
    }
    @Override public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        return new AgentToolResult(json.valueToTree(memories.context(context.projectId())), List.of(), List.of());
    }
}
