package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;

import java.util.Set;

/**
 * PROJECT_HEALTH Skill：检查项目健康度。
 */
public final class ProjectHealthSkill implements AgentSkill {

    private static final String INSTRUCTION = """
            你是项目健康检查助手。
            - 按顺序调用工具获取项目快照、逾期任务、里程碑、负载。
            - 基于工具返回的事实生成健康报告。
            - 不要猜测或编造数据。
            - 如果某个工具失败，说明缺失信息，不要伪造成功。
            """;

    private static final String OUTPUT_CONTRACT = """
            输出格式：
            1. 项目概况（名称、状态、进度）
            2. 任务健康度（总数、各状态分布、逾期任务）
            3. 里程碑状态（已完成/进行中/逾期）
            4. 团队负载（任务分配情况）
            5. 风险提示（如有）
            6. 建议（如有）
            """;

    @Override
    public String code() { return "PROJECT_HEALTH"; }

    @Override
    public String displayName() { return "检查项目健康度"; }

    @Override
    public String description() { return "检查项目整体健康状况，包括任务、里程碑和团队负载"; }

    @Override
    public Set<String> recommendedRoutes() { return Set.of("DASHBOARD", "TASK_BOARD", "AGENT"); }

    @Override
    public Set<String> allowedTools() {
        return Set.of("get_project_overview", "list_tasks", "list_milestones",
                "get_project_dashboard", "check_project_progress",
                "list_recent_audit_summaries", "analyze_project_risks");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("focus")
                .put("type", "string")
                .put("description", "可选的关注领域，如 'tasks', 'milestones', 'team'");
        return schema;
    }

    @Override
    public boolean allowWriteTools() { return false; }

    @Override
    public AgentRuntimeLimits defaultLimits() { return AgentRuntimeLimits.forSkill("PROJECT_HEALTH"); }

    @Override
    public String instruction() { return INSTRUCTION; }

    @Override
    public String outputContract() { return OUTPUT_CONTRACT; }
}
