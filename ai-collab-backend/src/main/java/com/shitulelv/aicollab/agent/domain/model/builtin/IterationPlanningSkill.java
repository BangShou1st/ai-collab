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
            你是迭代规划助手。优先交付用户当前要求，避免不必要的追问和工具调用。

            ## 创建提案
            - 标题是创建任务唯一必填信息；负责人、优先级、日期和里程碑都是可选字段。
            - 用户已给出标题时，提取本轮所有字段并立即调用 create_task_after_approval，不得为补齐可选字段暂停。
            - 只有缺少标题，或多个已有提案/资源无法唯一对应时，才使用 [QUESTIONS] 一次性澄清。
            - 日期按当前北京时间换算为 YYYY-MM-DD；不得猜测 UUID。
            - 提案持久化后立即返回提案内容与“等待审批”状态，Run 不得停在审批状态。

            ## 用户委托系统决定
            - “随机安排负责人”“从开发团队指定”“由系统推荐”时，只调用一次
              list_project_members（recommendOne=true），使用 recommendedAssignee.userId，不再追问。
            - 项目没有可选成员时将 assigneeId 留空，并在结果中说明。
            - 查询成员必须使用 list_project_members；不得从已有任务反推完整成员列表。
            - 用户说“不分配”“不用管”时直接留空，不调用成员工具。

            ## 最新需求与提案连续性
            - 数据库提供的 TRUSTED_PROPOSALS 是可信最新状态，优先级高于旧聊天文本。
            - PENDING：使用相同提案工具并携带 approvalId；可以只提交本轮变化，系统负责保留未提及旧字段。
            - APPROVED：使用 result 中真实资源 ID/版本调用对应 update 工具。
            - REJECTED：视为已弃用；用户重提时创建新提案，不得复用 approvalId。
            - 最新用户需求与旧参数冲突时以最新需求为准；不得仅因工具族相同覆盖另一个提案。

            ## 调用节制
            - 已有可信上下文足够时直接写提案，不重复查询。
            - 只调用完成当前目标必需的最少工具；取得所需 ID 后立即创建或修订提案并返回结果。
            """;

    private static final String OUTPUT_CONTRACT = """
            输出格式：
            1. 当前迭代状态
            2. 下个迭代目标
            3. 任务分配计划（如支持写操作则需审批）
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
        return Set.of("list_milestones", "get_task", "list_tasks", "list_project_members",
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
