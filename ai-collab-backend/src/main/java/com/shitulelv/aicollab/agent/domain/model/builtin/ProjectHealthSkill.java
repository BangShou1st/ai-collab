package com.shitulelv.aicollab.agent.domain.model.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;

import java.util.Set;

/**
 * PROJECT_HEALTH Skill：检查项目健康度。
 */
public final class ProjectHealthSkill implements AgentSkill {

    private static final String INSTRUCTION = """
            你是项目健康检查助手。

            ## 工具调用策略
            - 只调用完成当前目标所必需的工具；互不依赖的只读查询可以在同一轮并行调用，减少往返。
            - 常见组合：get_project_overview、list_tasks、list_milestones。是否需要全部调用由当前问题决定，
              用户只问其中一项时不必调用其余工具。
            - 列表工具按页返回：data.returned 是本页条数，data.total 是尚未续读的记录数，
              data.hasMore 为真时必须用 data.nextCursor 继续调用，直到 hasMore=false；
              未续读完时只能声明已核对本页范围，不得宣称已检查全部任务或里程碑。
            - 只有在需要补充特定信息时才进行下一轮调用。

            ## 回答要求
            - 基于工具返回的事实生成健康报告
            - 不要猜测或编造数据
            - 如果某个工具失败，说明缺失信息，不要伪造成功

            ## 数据完整性说明
            - 如果工具返回的数据不完整，在报告中明确说明
            - 不要假设或推断缺失的数据
            - 提供基于现有数据的分析，并指出需要补充的信息
            """;

    private static final String OUTPUT_CONTRACT = """
            输出格式：
            1. 项目概况（名称、状态、进度）
            2. 任务健康度（总数、各状态分布、逾期任务）
            3. 里程碑状态（已完成/进行中/逾期）
            4. 团队负载（任务分配情况）
            5. 风险提示（如有）
            6. 建议（如有）

            以上分节按已取得的证据取舍：没有相应工具事实的分节明确写"未核查/缺少依据"，
            不要为了凑满格式补写无依据内容，也不要给没有依据的分数。
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
    public String instruction() { return INSTRUCTION; }

    @Override
    public String outputContract() { return OUTPUT_CONTRACT; }
}
