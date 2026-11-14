package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;
import com.shitulelv.aicollab.agent.domain.model.AgentScenarioCategory;

import java.util.Set;

/**
 * MEETING_TO_TASKS Scenario：从会议纪要生成任务。
 */
public final class MeetingToTasksScenario implements AgentScenario {

    @Override
    public String code() { return "MEETING_TO_TASKS"; }

    @Override
    public String displayName() { return "会议纪要转任务"; }

    @Override
    public String description() { return "从会议纪要中提取待办事项并创建任务"; }

    @Override
    public Set<String> entryRoutes() { return Set.of("DOCUMENT_DETAIL", "TASK_BOARD", "AGENT"); }

    @Override
    public Set<String> grantedTools() {
        return Set.of("list_project_documents", "get_document_outline", "read_document_section", "search_project_knowledge", "list_tasks", "get_task", "list_project_members",
                "create_task_after_approval");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("documentId")
                .put("type", "string")
                .put("format", "uuid")
                .put("description", "会议纪要文档 ID");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return true; }

    @Override
    public AgentScenarioCategory category() { return AgentScenarioCategory.WORKFLOW; }
}
