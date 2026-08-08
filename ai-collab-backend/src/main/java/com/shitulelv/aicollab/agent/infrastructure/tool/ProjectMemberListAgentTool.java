package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.project.application.service.ProjectMemberApplicationService;
import com.shitulelv.aicollab.project.application.view.MemberView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class ProjectMemberListAgentTool implements AgentTool {
    private final ProjectMemberApplicationService members;
    private final ObjectMapper json;

    public ProjectMemberListAgentTool(ProjectMemberApplicationService members, ObjectMapper json) {
        this.members = members;
        this.json = json;
    }

    @Override public String name() { return "list_project_members"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "列出真实项目成员；用户要求随机或系统分配负责人时可返回一个稳定推荐人选",
                """
                {"type":"object","additionalProperties":false,"properties":{
                  "recommendOne":{"type":"boolean","description":"是否返回一个稳定推荐负责人"}
                }}
                """, false);
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("recommendOne"));
        boolean recommendOne = arguments.path("recommendOne").asBoolean(false);
        List<MemberView> values = members.list(context.projectId(), context.userId()).stream()
                .sorted(Comparator.comparing(member -> member.userId().toString()))
                .toList();
        ArrayNode items = json.createArrayNode();
        values.stream().map(this::narrow).forEach(items::add);
        ObjectNode data = json.createObjectNode();
        data.set("items", items);
        data.put("count", items.size());

        if (recommendOne && !values.isEmpty()) {
            List<MemberView> candidates = values.stream()
                    .filter(member -> member.role() != ProjectRole.OWNER)
                    .toList();
            if (candidates.isEmpty()) candidates = values;
            int index = Math.floorMod(context.runId().hashCode(), candidates.size());
            data.set("recommendedAssignee", narrow(candidates.get(index)));
        }
        return new AgentToolResult(data, List.of(), List.of());
    }

    private ObjectNode narrow(MemberView member) {
        ObjectNode value = json.createObjectNode();
        value.put("userId", member.userId().toString());
        value.put("username", member.username());
        value.put("displayName", member.displayName());
        value.put("role", member.role().name());
        return value;
    }
}
