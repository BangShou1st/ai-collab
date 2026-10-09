package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
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

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(name(),
                "按 ID 读取单个任务的完整事实：标题、完整描述、状态、优先级、负责人、"
                        + "日期、版本、依赖计数与依赖 ID。list_tasks 返回的 description 是截断摘要，"
                        + "需要完整描述时用本工具读取；taskId 必须来自真实工具结果或用户提供的明确 ID，不得猜测。",
                """
                {"type":"object","additionalProperties":false,"required":["taskId"],
                 "properties":{"taskId":{"type":"string","format":"uuid",
                   "description":"任务 ID，来自 list_tasks 的 items[].id 或用户明确提供的 ID"}}}
                """,
                false);
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("taskId"));
        UUID id = AgentToolArguments.uuid(arguments, "taskId", true);
        return new AgentToolResult(
                json.valueToTree(tasks.get(context.projectId(), id, context.userId())),
                List.of(), List.of());
    }
}
