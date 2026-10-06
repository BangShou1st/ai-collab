package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;

import java.util.Set;

/**
 * DELIVERY_READINESS Skill：交付就绪检查。
 */
public final class DeliveryReadinessSkill implements AgentSkill {

    private static final String INSTRUCTION = """
            你是交付就绪检查助手。

            ## 检查策略
            - 使用工具检查任务完成度、里程碑状态与项目进度；这些是当前 Skill 实际可用的资料范围。
            - 本 Skill 的白名单不包含文档读取工具：不要宣称已核查文档完整性与交付物清单；
              需要这部分结论时明确写"文档未核查（当前工具范围不包含文档读取）"。
            - 列表工具按页返回：data.returned 是本页条数，data.total 是尚未续读的记录数，
              data.hasMore 为真时必须用 data.nextCursor 继续调用，直到 hasMore=false；
              未续读完时只能声明已核对本页范围，不得宣称已检查全部任务或里程碑。
            - 只调用完成当前目标所必需的工具；互不依赖的只读查询可以同轮并行。
            - 评估项目是否达到交付标准；不要猜测，基于工具返回的事实判断。
            - 没有日期、依赖或文档依据时，明确说无法判定是否延期，不能仅凭 TODO 断言延期。

            ## 用户交互
            - 如果用户没有指定检查范围，默认检查整个项目。
            - 如果用户指定了里程碑，只检查该里程碑相关的交付物。
            - 如果信息不足以做出判断，使用 [QUESTIONS] 询问用户。
            """;

    private static final String OUTPUT_CONTRACT = """
            输出格式：
            1. 就绪度判断（达到/未达到/证据不足，并说明判断依据）
            2. 已完成项
            3. 未完成项
            4. 文档完整性（本 Skill 无文档工具时写"未核查"）
            5. 风险项
            6. 建议

            只写已取得证据支撑的内容；依据不足的分节明确写"未核查/缺少依据"，
            不要给没有依据的分数，也不要为了凑满格式补写无依据内容。
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
    public String instruction() { return INSTRUCTION; }

    @Override
    public String outputContract() { return OUTPUT_CONTRACT; }
}
