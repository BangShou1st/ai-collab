package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;

import java.util.Set;

/**
 * ITERATION_PLANNING Skill：迭代规划。
 */
public final class IterationPlanningSkill implements AgentSkill {

    private static final String INSTRUCTION = """
            你是迭代规划助手。
            - 使用工具获取里程碑、任务列表、工作负载。
            - 基于当前状态规划下一个迭代。
            - 写操作需要审批。
            """;

    private static final String OUTPUT_CONTRACT = """
            输出格式：
            1. 当前迭代状态
            2. 下个迭代目标
            3. 任务分配计划（需审批）
            4. 风险和依赖
            """;

    @Override
    public String code() { return "ITERATION_PLANNING"; }

    @Override
    public String displayName() { return "迭代规划"; }

    @Override
    public String description() { return "规划项目迭代，分配任务到里程碑"; }

    @Override
    public Set<String> recommendedRoutes() { return Set.of("MILESTONE_LIST", "TASK_BOARD", "PLANNING", "AGENT"); }

    @Override
    public Set<String> allowedTools() {
        return Set.of("list_milestones", "get_task", "list_tasks",
                "create_task_after_approval", "update_task_after_approval",
                "create_milestone_after_approval", "update_milestone_after_approval",
                "create_memory_after_approval");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("milestoneId")
                .put("type", "string")
                .put("format", "uuid")
                .put("description", "目标里程碑 ID");
        properties.putObject("focus")
                .put("type", "string")
                .put("description", "规划重点，如 'backend', 'frontend', 'all'");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return true; }

    @Override
    public AgentRuntimeLimits defaultLimits() { return AgentRuntimeLimits.forSkill("ITERATION_PLANNING"); }

    @Override
    public String instruction() { return INSTRUCTION; }

    @Override
    public String outputContract() { return OUTPUT_CONTRACT; }
}
