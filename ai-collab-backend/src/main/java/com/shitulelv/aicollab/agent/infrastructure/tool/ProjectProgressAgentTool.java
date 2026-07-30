package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentInference;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.project.application.service.ProjectDashboardQueryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ProjectProgressAgentTool implements AgentTool {
    private final ProjectDashboardQueryService dashboard;
    private final ObjectMapper json;

    public ProjectProgressAgentTool(ProjectDashboardQueryService dashboard, ObjectMapper json) {
        this.dashboard = dashboard;
        this.json = json;
    }

    @Override public String name() { return "check_project_progress"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of());
        var value = dashboard.getDashboard(context.projectId(), context.userId());
        return new AgentToolResult(
                json.valueToTree(value),
                List.of(),
                List.of(new AgentInference(
                        "进度判断应以返回的确定性项目统计为依据",
                        "项目 Dashboard 统计",
                        "HIGH")));
    }
}
