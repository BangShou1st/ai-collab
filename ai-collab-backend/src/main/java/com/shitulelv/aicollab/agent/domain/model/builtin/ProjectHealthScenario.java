package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;

import java.util.Set;

/**
 * PROJECT_HEALTH Scenario：检查项目健康度。
 */
public final class ProjectHealthScenario implements AgentScenario {

    @Override
    public String code() { return "PROJECT_HEALTH"; }

    @Override
    public String displayName() { return "检查项目健康度"; }

    @Override
    public String description() { return "检查项目整体健康状况，包括任务、里程碑和团队负载"; }

    @Override
    public Set<String> entryRoutes() { return Set.of("DASHBOARD", "TASK_BOARD", "AGENT"); }

    @Override
    public Set<String> grantedTools() {
        return Set.of("get_project_overview", "list_tasks", "list_milestones",
                "get_project_dashboard", "check_project_progress",
                "list_recent_audit_summaries", "analyze_project_risks");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("focus")
                .put("type", "string")
                .put("description", "可选的关注领域，如 'tasks', 'milestones', 'team'");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return false; }
}
