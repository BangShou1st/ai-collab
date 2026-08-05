package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;

import java.util.Set;

/**
 * WEEKLY_REPORT Skill：生成周报。
 */
public final class WeeklyReportSkill implements AgentSkill {

    private static final String INSTRUCTION = """
            你是周报生成助手。
            - 使用工具获取项目近期活动、任务状态、里程碑进展。
            - 基于事实生成结构化周报。
            - 不要猜测或编造数据。
            - 日期范围默认为最近 7 天。
            """;

    private static final String OUTPUT_CONTRACT = """
            输出格式：
            1. 本周概要（1-2 句话）
            2. 完成的任务（列出）
            3. 进行中的任务（列出）
            4. 里程碑进展
            5. 遇到的问题和风险
            6. 下周计划
            """;

    @Override
    public String code() { return "WEEKLY_REPORT"; }

    @Override
    public String displayName() { return "生成周报"; }

    @Override
    public String description() { return "基于项目数据生成结构化周报"; }

    @Override
    public Set<String> recommendedRoutes() { return Set.of("DASHBOARD", "TASK_BOARD", "AGENT"); }

    @Override
    public Set<String> allowedTools() {
        return Set.of("get_project_overview", "list_tasks", "list_milestones",
                "draft_weekly_report", "get_project_dashboard", "list_project_memories");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("days")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", 30)
                .put("description", "天数，默认 7");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return false; }

    @Override
    public AgentRuntimeLimits defaultLimits() { return AgentRuntimeLimits.forSkill("WEEKLY_REPORT"); }

    @Override
    public String instruction() { return INSTRUCTION; }

    @Override
    public String outputContract() { return OUTPUT_CONTRACT; }
}
