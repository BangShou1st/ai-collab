package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.work.api.dto.UpdateTaskRequest;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class UpdateTaskApprovalAgentTool extends AbstractApprovalWriteAgentTool {
    private final TaskApplicationService tasks;

    public UpdateTaskApprovalAgentTool(ObjectMapper json, Validator validator, TaskApplicationService tasks) {
        super(json, validator);
        this.tasks = tasks;
    }

    @Override public String name() { return "update_task_after_approval"; }

    @Override
    public JsonNode normalize(AgentToolContext context, JsonNode arguments) {
        UUID taskId = requiredId(arguments, "taskId");
        JsonNode payload = arguments.has("changes") ? arguments.get("changes") : arguments;
        ObjectNode normalized = json.createObjectNode();
        normalized.put("taskId", taskId.toString());
        normalized.set("changes", tree(request(payload, UpdateTaskRequest.class)));
        return normalized;
    }

    @Override
    public JsonNode diff(AgentToolContext context, JsonNode normalized) {
        UUID taskId = UUID.fromString(normalized.path("taskId").asText());
        return tree(java.util.Map.of(
                "operation", "UPDATE",
                "before", tasks.get(context.projectId(), taskId, context.userId()),
                "after", normalized.get("changes")));
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        UUID taskId = requiredId(arguments, "taskId");
        UpdateTaskRequest request = request(arguments.get("changes"), UpdateTaskRequest.class);
        return new AgentToolResult(tree(tasks.update(
                context.projectId(), taskId, request, context.userId())), List.of(), List.of());
    }

    private static UUID requiredId(JsonNode arguments, String name) {
        try {
            return UUID.fromString(arguments.path(name).asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " 必须是 UUID", exception);
        }
    }
}
