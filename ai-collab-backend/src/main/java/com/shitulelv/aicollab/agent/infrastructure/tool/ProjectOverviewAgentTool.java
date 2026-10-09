package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.project.application.service.ProjectApplicationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ProjectOverviewAgentTool implements AgentTool {
    private final ProjectApplicationService projects;
    private final ObjectMapper json;

    public ProjectOverviewAgentTool(ProjectApplicationService projects, ObjectMapper json) {
        this.projects = projects;
        this.json = json;
    }

    @Override public String name() { return "get_project_overview"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "获取当前项目的基本信息（名称、描述、状态等）。不接受任何参数。",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}",
                false);
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of());
        return new AgentToolResult(
                json.valueToTree(projects.get(context.projectId(), context.userId())),
                List.of(), List.of());
    }
}
