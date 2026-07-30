package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentInference;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.work.application.service.WorkReportService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ProjectRiskAgentTool implements AgentTool {
    private final WorkReportService reports;
    private final ObjectMapper json;

    public ProjectRiskAgentTool(WorkReportService reports, ObjectMapper json) {
        this.reports = reports;
        this.json = json;
    }

    @Override public String name() { return "analyze_project_risks"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of());
        var value = reports.getRiskAnalysis(context.projectId(), context.userId());
        return new AgentToolResult(
                json.valueToTree(value),
                List.of(),
                List.of(new AgentInference(
                        value.summary().overallAssessment(),
                        "确定性风险统计",
                        "HIGH")));
    }
}
