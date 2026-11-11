package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;

import java.util.Set;

/**
 * DELIVERY_READINESS Scenario：交付就绪检查。
 */
public final class DeliveryReadinessScenario implements AgentScenario {

    @Override
    public String code() { return "DELIVERY_READINESS"; }

    @Override
    public String displayName() { return "交付就绪检查"; }

    @Override
    public String description() { return "检查项目是否达到交付标准"; }

    @Override
    public Set<String> entryRoutes() { return Set.of("DASHBOARD", "TASK_BOARD", "AGENT"); }

    @Override
    public Set<String> grantedTools() {
        return Set.of("check_project_progress", "list_tasks", "list_milestones",
                "get_project_overview");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("milestoneId")
                .put("type", "string")
                .put("format", "uuid")
                .put("description", "可选的目标里程碑 ID");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return false; }
}
