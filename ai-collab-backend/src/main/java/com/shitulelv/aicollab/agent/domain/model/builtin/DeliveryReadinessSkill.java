package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;

import java.util.Set;

/**
 * DELIVERY_READINESS Skill：交付就绪检查。
 */
public final class DeliveryReadinessSkill implements AgentSkill {

    private static final String INSTRUCTION = """
            你是交付就绪检查助手。
            - 使用工具检查任务完成度、里程碑状态、文档完整性。
            - 评估项目是否达到交付标准。
            - 不要猜测，基于工具返回的事实判断。
            """;

    private static final String OUTPUT_CONTRACT = """
            输出格式：
            1. 交付就绪度评分（0-100）
            2. 已完成项
            3. 未完成项
            4. 文档完整性
            5. 风险项
            6. 建议
            """;

    @Override
    public String code() { return "DELIVERY_READINESS"; }

    @Override
    public String displayName() { return "交付就绪检查"; }

    @Override
    public String description() { return "检查项目是否达到交付标准"; }

    @Override
    public Set<String> recommendedRoutes() { return Set.of("DASHBOARD", "TASK_BOARD", "AGENT"); }

    @Override
    public Set<String> allowedTools() {
        return Set.of("check_project_progress", "list_tasks", "list_milestones",
                "get_project_overview");
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
                .put("description", "可选的目标里程碑 ID");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return false; }

    @Override
    public AgentRuntimeLimits defaultLimits() { return AgentRuntimeLimits.forSkill("DELIVERY_READINESS"); }

    @Override
    public String instruction() { return INSTRUCTION; }

    @Override
    public String outputContract() { return OUTPUT_CONTRACT; }
}
