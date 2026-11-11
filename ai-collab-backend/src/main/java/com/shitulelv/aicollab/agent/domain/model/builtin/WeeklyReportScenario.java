package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;

import java.util.Set;

/**
 * WEEKLY_REPORT Scenario：生成周报。
 */
public final class WeeklyReportScenario implements AgentScenario {

    @Override
    public String code() { return "WEEKLY_REPORT"; }

    @Override
    public String displayName() { return "生成周报"; }

    @Override
    public String description() { return "基于项目数据生成结构化周报"; }

    @Override
    public Set<String> entryRoutes() { return Set.of("DASHBOARD", "TASK_BOARD", "AGENT"); }

    @Override
    public Set<String> grantedTools() {
        return Set.of("get_project_overview", "list_tasks", "list_milestones",
                "draft_weekly_report", "get_project_dashboard", "list_project_memories");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("days")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", 30)
                .put("description", "天数，默认 7");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return false; }

    @Override
    public boolean allowExternalTools() { return true; }
}
