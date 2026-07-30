package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.work.api.dto.CreateTaskRequest;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CreateTaskApprovalAgentTool extends AbstractApprovalWriteAgentTool {
    private final TaskApplicationService tasks;

    public CreateTaskApprovalAgentTool(ObjectMapper json, Validator validator, TaskApplicationService tasks) {
        super(json, validator);
        this.tasks = tasks;
    }

    @Override public String name() { return "create_task_after_approval"; }

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.fromJson(
                name(),
                "创建任务提案；必须经过项目管理员批准后才写入任务",
                """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["title"],
                  "properties":{
                    "title":{"type":"string","minLength":1,"maxLength":160},
                    "description":{"type":["string","null"],"maxLength":4000},
                    "milestoneId":{"type":["string","null"],"format":"uuid"},
                    "assigneeId":{"type":["string","null"],"format":"uuid"},
                    "status":{"type":["string","null"],"enum":["TODO","IN_PROGRESS","BLOCKED","DONE","CANCELED",null]},
                    "priority":{"type":["string","null"],"enum":["LOW","MEDIUM","HIGH","URGENT",null]},
                    "estimateHours":{"type":["number","null"],"minimum":0.5,"maximum":80},
                    "startDate":{"type":["string","null"],"format":"date"},
                    "dueDate":{"type":["string","null"],"format":"date"}
                  }
                }
                """,
                true);
    }

    @Override
    public JsonNode normalize(AgentToolContext context, JsonNode arguments) {
        return tree(request(arguments, CreateTaskRequest.class));
    }

    @Override
    public JsonNode diff(AgentToolContext context, JsonNode normalizedArguments) {
        return tree(java.util.Map.of("operation", "CREATE", "after", normalizedArguments));
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        return new AgentToolResult(tree(tasks.create(
                context.projectId(), request(arguments, CreateTaskRequest.class), context.userId())),
                List.of(), List.of());
    }
}
