package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.project.application.service.AuditLogQueryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class AuditSummaryAgentTool implements AgentTool {
    private final AuditLogQueryService audit;
    private final ObjectMapper json;

    public AuditSummaryAgentTool(AuditLogQueryService audit, ObjectMapper json) {
        this.audit = audit;
        this.json = json;
    }

    @Override public String name() { return "list_recent_audit_summaries"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("limit"));
        int limit = AgentToolArguments.integer(arguments, "limit", 10, 1, 20);
        ObjectNode data = json.createObjectNode();
        data.set("items", json.valueToTree(
                audit.recentDashboardActivities(
                        context.projectId(), context.userId(), limit)));
        return new AgentToolResult(data, List.of(), List.of());
    }
}
