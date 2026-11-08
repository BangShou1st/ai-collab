package com.shitulelv.aicollab.planning.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.application.service.MilestoneApplicationService;
import com.shitulelv.aicollab.work.application.view.MilestoneView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * {@code get_project_milestones}：查询项目既有里程碑，让新生成的规划对齐已有里程碑
 * （设计文档 §5.2）。复用 work 模块 {@link MilestoneApplicationService}。
 */
@Component
public class GetProjectMilestonesPlanningTool implements PlanningReadOnlyTool {

    /** 单个里程碑描述进入结果的字符上限。 */
    static final int DESCRIPTION_CHARS = 300;
    /** 单次返回里程碑数上限（有界查询）。 */
    static final int MAX_RETURNED = 50;

    private final MilestoneApplicationService milestones;
    private final ProjectAccessGuard access;
    private final ObjectMapper json;

    public GetProjectMilestonesPlanningTool(MilestoneApplicationService milestones,
                                            ProjectAccessGuard access, ObjectMapper json) {
        this.milestones = milestones;
        this.access = access;
        this.json = json;
    }

    @Override public String name() { return "get_project_milestones"; }

    @Override public String description() {
        return "列出项目现有里程碑（名称/状态/目标日期），用于让新规划对齐已有里程碑、避免重复。无参数。";
    }

    @Override
    public JsonNode execute(JsonNode arguments, UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        List<MilestoneView> matched = milestones.list(projectId, userId);
        ObjectNode data = json.createObjectNode();
        data.put("total", matched.size());
        data.put("returned", Math.min(MAX_RETURNED, matched.size()));
        ArrayNode items = data.putArray("milestones");
        for (MilestoneView milestone : matched.stream().limit(MAX_RETURNED).toList()) {
            ObjectNode item = items.addObject();
            item.put("id", milestone.id().toString());
            item.put("name", milestone.name());
            item.put("status", milestone.status().name());
            if (milestone.startDate() != null) item.put("startDate", milestone.startDate().toString());
            if (milestone.targetDate() != null) item.put("targetDate", milestone.targetDate().toString());
            if (milestone.description() != null) {
                String description = milestone.description();
                if (description.length() > DESCRIPTION_CHARS) {
                    description = description.substring(0, DESCRIPTION_CHARS) + "… [truncated]";
                }
                item.put("description", description);
            }
        }
        data.put("fieldGuide", "milestones 是项目当前真实里程碑记录；新规划的里程碑应与它们在主题与日期上衔接，不得重复创建同名里程碑。");
        return data;
    }
}
