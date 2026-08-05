package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;

import java.util.Set;

/**
 * MEETING_TO_TASKS Skill：从会议纪要生成任务。
 */
public final class MeetingToTasksSkill implements AgentSkill {

    private static final String INSTRUCTION = """
            你是会议纪要转任务助手。
            - 使用工具获取文档内容和现有任务。
            - 从会议纪要中提取待办事项。
            - 去重：检查是否已有相同任务。
            - 生成结构化的任务创建请求。
            - 写操作需要审批。
            """;

    private static final String OUTPUT_CONTRACT = """
            输出格式：
            1. 会议纪要摘要
            2. 提取的待办事项列表
            3. 去重说明（如有重复）
            4. 任务创建计划（需审批）
            """;

    @Override
    public String code() { return "MEETING_TO_TASKS"; }

    @Override
    public String displayName() { return "会议纪要转任务"; }

    @Override
    public String description() { return "从会议纪要中提取待办事项并创建任务"; }

    @Override
    public Set<String> recommendedRoutes() { return Set.of("DOCUMENT_DETAIL", "TASK_BOARD", "AGENT"); }

    @Override
    public Set<String> allowedTools() {
        return Set.of("search_project_knowledge", "list_tasks", "get_task",
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
    public AgentRuntimeLimits defaultLimits() { return AgentRuntimeLimits.forSkill("MEETING_TO_TASKS"); }

    @Override
    public String instruction() { return INSTRUCTION; }

    @Override
    public String outputContract() { return OUTPUT_CONTRACT; }
}
