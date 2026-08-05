package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.work.api.dto.UpdateTaskRequest;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.TaskView;
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

    /**
     * 执行前重新校验。
     * 检查：目标实体仍存在、属于当前项目、当前版本等于审批提案版本、
     * approver 仍有权限、更新字段仍合法、业务条件仍成立。
     */
    @Override
    public void revalidate(AgentToolContext context, JsonNode arguments) {
        UUID taskId = requiredId(arguments, "taskId");

        // 1. 目标实体仍存在且属于当前项目
        TaskView task = tasks.get(context.projectId(), taskId, context.userId());

        // 2. 参数仍符合 Schema（通过 request 方法校验）
        UpdateTaskRequest request = request(arguments.get("changes"), UpdateTaskRequest.class);

        if (request.version() == null || request.version() != task.version()) {
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_VERSION_CONFLICT);
        }

        // 3. 业务条件仍成立（例如，任务不能已完成才能更新）
        // 注意：具体的业务条件校验在 TaskApplicationService.update 中会进行
    }

    private static UUID requiredId(JsonNode arguments, String name) {
        try {
            return UUID.fromString(arguments.path(name).asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " 必须是 UUID", exception);
        }
    }
}
