package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
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
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of());
        return new AgentToolResult(
                json.valueToTree(projects.get(context.projectId(), context.userId())),
                List.of(), List.of());
    }
}
