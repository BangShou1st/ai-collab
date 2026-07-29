package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class MilestoneListAgentTool implements AgentTool {
    private final MilestoneApplicationService milestones;
    private final ObjectMapper json;

    public MilestoneListAgentTool(MilestoneApplicationService milestones, ObjectMapper json) {
        this.milestones = milestones;
        this.json = json;
    }

    @Override public String name() { return "list_milestones"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("limit"));
        int limit = AgentToolArguments.integer(arguments, "limit", 20, 1, 50);
        var all = milestones.list(context.projectId(), context.userId());
        ObjectNode data = json.createObjectNode();
        data.set("items", json.valueToTree(all.stream().limit(limit).toList()));
        data.put("truncated", all.size() > limit);
        return new AgentToolResult(data, List.of(), List.of());
    }
}
