package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
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
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "读取项目最近的活动审计摘要（谁在什么时候做了什么），用于回答“最近有什么变动”。"
                        + "按时间倒序返回，默认 10 条。",
                """
                {"type":"object","additionalProperties":false,
                 "properties":{"limit":{"type":"integer","minimum":1,"maximum":20,"default":10,
                   "description":"返回条数上限，默认 10，最多 20"}}}
                """,
                false);
    }

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
