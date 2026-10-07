package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentInference;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
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

    /**
     * 返回确定性的周报统计事实（任务/里程碑统计、贡献者、亮点、风险），
     * 不在工具内部生成 AI 周报正文；叙述由模型基于返回统计组织。
     */
    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "获取最近 N 天的周报统计事实：任务与里程碑统计、主要贡献者、亮点与风险。"
                        + "返回的是确定性统计数据，不是成稿周报；叙述由你基于统计组织，不得虚构统计之外的数字。",
                """
                {"type":"object","additionalProperties":false,
                 "properties":{"days":{"type":"integer","minimum":1,"maximum":30,"default":7,
                   "description":"统计回溯天数，默认 7"}}}
                """,
                false);
    }

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
