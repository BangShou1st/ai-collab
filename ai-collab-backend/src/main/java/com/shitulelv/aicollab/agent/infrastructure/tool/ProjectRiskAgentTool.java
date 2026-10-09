package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentInference;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
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
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "获取确定性风险分析统计（逾期任务、依赖阻塞等）。不接受任何参数。",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}",
                false);
    }

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
