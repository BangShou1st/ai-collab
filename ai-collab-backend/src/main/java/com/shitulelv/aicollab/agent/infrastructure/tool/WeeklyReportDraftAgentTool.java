package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentInference;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.work.application.service.WorkReportService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class WeeklyReportDraftAgentTool implements AgentTool {
    private final WorkReportService reports;
    private final ObjectMapper json;

    public WeeklyReportDraftAgentTool(WorkReportService reports, ObjectMapper json) {
        this.reports = reports;
        this.json = json;
    }

    @Override public String name() { return "draft_weekly_report"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("days"));
        int days = AgentToolArguments.integer(arguments, "days", 7, 1, 30);
        var report = reports.getWeeklyReport(context.projectId(), context.userId(), days);
        ObjectNode data = json.createObjectNode();
        data.put("reportDate", report.reportDate().toString());
        data.set("taskStatistics", json.valueToTree(report.taskStats()));
        data.set("milestoneStatistics", json.valueToTree(report.milestoneStats()));
        data.set("topContributors", json.valueToTree(report.topContributors()));
        data.set("highlights", json.valueToTree(report.highlights()));
        data.set("risks", json.valueToTree(report.risks()));
        List<AgentInference> inferences = new ArrayList<>();
        report.highlights().forEach(value -> inferences.add(
                new AgentInference(value, "确定性周报统计", "HIGH")));
        report.risks().forEach(value -> inferences.add(
                new AgentInference(value, "确定性周报统计", "HIGH")));
        return new AgentToolResult(data, List.of(), inferences);
    }
}
