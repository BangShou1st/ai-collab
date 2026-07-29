package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.project.application.service.ProjectDashboardQueryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class DashboardAgentTool implements AgentTool {
    private final ProjectDashboardQueryService dashboard;
    private final ObjectMapper json;

    public DashboardAgentTool(ProjectDashboardQueryService dashboard, ObjectMapper json) {
        this.dashboard = dashboard;
        this.json = json;
    }

    @Override public String name() { return "get_project_dashboard"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of());
        return new AgentToolResult(
                json.valueToTree(dashboard.getDashboard(context.projectId(), context.userId())),
                List.of(), List.of());
    }
}
