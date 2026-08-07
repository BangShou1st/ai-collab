package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.api.dto.AgentMemoryRequest;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class CreateMemoryApprovalAgentTool extends AbstractApprovalWriteAgentTool {
    private final AgentMemoryService memories; private final ProjectAccessGuard access;
    public CreateMemoryApprovalAgentTool(ObjectMapper json, Validator validator,
            AgentMemoryService memories, ProjectAccessGuard access) {
        super(json, validator); this.memories = memories; this.access = access;
    }
    @Override public String name() { return "create_memory_after_approval"; }

    @Override
    public AgentProposalFamily proposalFamily() {
        return AgentProposalFamily.MEMORY_CREATE;
    }
    @Override public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(), "保存项目记忆提案，必须由项目管理员批准",
                """
                {"type":"object","additionalProperties":false,
                 "required":["type","title","content","sourceType"],"properties":{
                  "type":{"enum":["DECISION","PREFERENCE","CONSTRAINT","LESSON"]},
                  "title":{"type":"string","minLength":1,"maxLength":160},
                  "content":{"type":"string","minLength":1,"maxLength":2000},
                  "sourceType":{"type":"string","minLength":1,"maxLength":32},
                  "sourceId":{"type":["string","null"],"format":"uuid"},
                  "version":{"type":"integer","const":0}}}
                """, true);
    }
    @Override public JsonNode normalize(AgentToolContext context, JsonNode arguments) {
        AgentMemoryRequest value = request(arguments, AgentMemoryRequest.class);
        return tree(new AgentMemoryRequest(value.type(), value.title().strip(), value.content().strip(),
                value.sourceType().strip(), value.sourceId(), 0));
    }
    @Override public JsonNode diff(AgentToolContext context, JsonNode args) {
        return tree(Map.of("operation", "CREATE", "after", args));
    }
    @Override public void revalidate(AgentToolContext context, JsonNode arguments) {
        access.requireAdmin(context.projectId(), context.userId()); request(arguments, AgentMemoryRequest.class);
    }
    @Override public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        return new AgentToolResult(tree(memories.create(context.projectId(), context.userId(),
                request(arguments, AgentMemoryRequest.class))), List.of(), List.of());
    }
}
