package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentScenario;
import com.shitulelv.aicollab.agent.domain.model.AgentScenarioCategory;

import java.util.Set;

/**
 * ITERATION_PLANNING Scenario：迭代规划。
 */
public final class IterationPlanningScenario implements AgentScenario {

    @Override
    public String code() { return "ITERATION_PLANNING"; }

    @Override
    public String displayName() { return "迭代规划"; }

    @Override
    public String description() { return "规划项目迭代，分配任务到里程碑"; }

    @Override
    public Set<String> entryRoutes() { return Set.of("MILESTONE_LIST", "TASK_BOARD", "PLANNING", "AGENT"); }

    @Override
    public Set<String> grantedTools() {
        return Set.of("list_project_documents","get_document_outline","read_document_section","search_project_knowledge",
                "list_task_plans","get_task_plan","get_task_plan_progress","start_task_plan","repair_task_plan","cancel_task_plan_generation",
                "get_project_overview","list_milestones", "get_task", "list_tasks", "list_project_members",
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
    public AgentScenarioCategory category() { return AgentScenarioCategory.WORKFLOW; }

    /**
     * 本 Scenario 的核心业务动作：完整规划生成（受理草稿）。
     * 是否被当前目标要求由运行目标确定性判定（"生成…规划"且未被否定），
     * 只影响预算收尾时的完成状态判定，不放开权限或审批。
     */
    @Override
    public Set<String> coreActionTools() { return Set.of("start_task_plan"); }
}
