package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.view.MilestoneView;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class MilestoneListAgentTool implements AgentTool {
    /** 单个里程碑描述进入模型视图的字符上限。 */
    static final int DESCRIPTION_CHARS = 300;

    private final MilestoneApplicationService milestones;
    private final ObjectMapper json;

    public MilestoneListAgentTool(MilestoneApplicationService milestones, ObjectMapper json) {
        this.milestones = milestones;
        this.json = json;
    }

    @Override public String name() { return "list_milestones"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override
    public com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition definition() {
        return com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition.fromJson(name(),
                "列出项目里程碑。按 id 升序稳定排序；一页最多 50 条，默认 20 条。"
                        + "返回 data.returned（本页条数）、data.total（尚未续读的记录数）、"
                        + "data.hasMore（是否还有后续）与 data.nextCursor（第一条未返回里程碑的 id）。"
                        + "需要检查全部里程碑时把 nextCursor 作为 cursor 继续调用，直到 hasMore=false；"
                        + "续读既不跳过未返回记录，也不重复已返回记录。"
                        + "未按 nextCursor 续读时明确说明只核对了本页范围，不得宣称已检查全部里程碑。", """
                {"type":"object","additionalProperties":false,"properties":{
                  "status":{"type":"string","enum":["PLANNED","ACTIVE","COMPLETED","CANCELED"]},
                  "cursor":{"type":"string","format":"uuid","description":"上一页 data.nextCursor，即第一条未返回里程碑的 id；服务端从它本身开始返回"},
                  "limit":{"type":"integer","minimum":1,"maximum":50}}}
                """,false);
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(arguments, Set.of("limit", "status", "cursor"));
        int limit = AgentToolArguments.integer(arguments, "limit", 20, 1, 50);
        String status = AgentToolArguments.text(arguments, "status", 30, false);
        var cursor = AgentToolArguments.uuid(arguments, "cursor", false);
        List<MilestoneView> matched = milestones.list(context.projectId(), context.userId()).stream()
                .filter(milestone -> status == null || milestone.status().name().equals(status))
                .sorted(Comparator.comparing(milestone -> milestone.id().toString()))
                .toList();
        List<MilestoneView> remaining = ListPageContract.from(
                matched, cursor == null ? null : cursor.toString(), milestone -> milestone.id().toString());
        ObjectNode data = ListPageContract.page(json, remaining, limit, this::narrow, m -> m.id().toString(),
                "id ASC", page -> page.put("fieldGuide",
                        "items 是本次查询的真实里程碑记录。name=名称，status=状态，"
                        + "startDate/endDate/targetDate=计划与目标日期，description 是截断后的摘要。"
                        + "returned 是本页条数，total 是尚未续读的记录数，"
                        + "nextCursor 是第一条未返回里程碑的 id；未按它续读到 hasMore=false 时，"
                        + "只能声明已核对本页范围。外层 status=SUCCEEDED 仅表示工具调用成功，不是里程碑状态。"));
        return new AgentToolResult(data, List.of(), List.of());
    }

    private ObjectNode narrow(MilestoneView milestone) {
        ObjectNode value = json.createObjectNode();
        value.put("id", milestone.id().toString());
        value.put("name", milestone.name());
        value.put("description", TaskListAgentTool.truncate(milestone.description(), DESCRIPTION_CHARS));
        value.put("status", milestone.status().name());
        value.put("startDate", milestone.startDate() == null ? null : milestone.startDate().toString());
        value.put("endDate", milestone.endDate() == null ? null : milestone.endDate().toString());
        value.put("targetDate", milestone.targetDate() == null ? null : milestone.targetDate().toString());
        value.put("sortOrder", milestone.sortOrder());
        value.put("version", milestone.version());
        return value;
    }
}
