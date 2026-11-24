package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class TaskGetAgentTool implements AgentTool {
    private final TaskApplicationService tasks;
    private final ObjectMapper json;

    public TaskGetAgentTool(TaskApplicationService tasks, ObjectMapper json) {
        this.tasks = tasks;
        this.json = json;
    }

    @Override public String name() { return "get_task"; }
    @Override public boolean writesBusinessData() { return false; }
    @Override public com.shitulelv.aicollab.agent.domain.tool.AgentToolExecutionSafety executionSafety() {
        return com.shitulelv.aicollab.agent.domain.tool.AgentToolExecutionSafety.SAFE_READ;
    }

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "按 ID 读取单个任务的完整事实：标题、完整描述、状态、优先级、负责人、"
                        + "日期、版本、依赖计数与依赖 ID。list_tasks 返回的 description 是截断摘要，"
                        + "需要完整描述时用本工具读取；不传 taskId 时优先回退当前页面选中的任务，"
                        + "其余场景 taskId 必须来自真实工具结果或用户明确提供，不得猜测。",
                """
                {"type":"object","additionalProperties":false,
                 "properties":{"taskId":{"type":"string","format":"uuid",
                   "description":"任务 ID（可选）；缺省时回退到当前页面上下文选中的 taskId"}}}
                """,
                false);
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("taskId"));
        UUID id = AgentToolArguments.uuid(arguments, "taskId", false);
        if (id == null && context.page() != null) {
            id = context.page().selectedTaskId();
        }
        if (id == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "缺少参数：taskId（当前页面也未选中任务）");
        }
        return new AgentToolResult(
                json.valueToTree(tasks.get(context.projectId(), id, context.userId())),
                List.of(), List.of());
    }
}
