package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
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
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("taskId"));
        UUID id = AgentToolArguments.uuid(arguments, "taskId", true);
        return new AgentToolResult(
                json.valueToTree(tasks.get(context.projectId(), id, context.userId())),
                List.of(), List.of());
    }
}
