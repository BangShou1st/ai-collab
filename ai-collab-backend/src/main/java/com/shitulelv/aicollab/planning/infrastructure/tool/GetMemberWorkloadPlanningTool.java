package com.shitulelv.aicollab.planning.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.application.service.WorkVisualizationService;
import com.shitulelv.aicollab.work.application.view.MemberLoadView;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@code get_member_workload}：按成员负载推荐 assignee（设计文档 §5.2）。
 * 复用 {@link WorkVisualizationService#getMemberLoad} 的负载口径——
 * 全部金额字段均为估算工时（系统无实际工时），且视图不含容量数据，
 * 不得输出"超负荷"结论，只提供"当前未完成估算工时"等事实。
 */
@Component
public class GetMemberWorkloadPlanningTool implements PlanningReadOnlyTool {

    private final WorkVisualizationService workload;
    private final ProjectAccessGuard access;
    private final ObjectMapper json;

    public GetMemberWorkloadPlanningTool(WorkVisualizationService workload, ProjectAccessGuard access,
                                         ObjectMapper json) {
        this.workload = workload;
        this.access = access;
        this.json = json;
    }

    @Override public String name() { return "get_member_workload"; }

    @Override public String description() {
        return "查询项目成员当前负载（未完成任务数、未完成估算工时、逾期任务数），用于把任务分配给合适的负责人。"
                + "无参数。负载口径是估算工时而非实际投入，不含容量上限，只能作为相对参考。";
    }

    @Override
    public JsonNode execute(JsonNode arguments, UUID projectId, UUID userId) {
        // getMemberLoad 内部同样有 requireMember，这里显式校验保持工具边界一致
        access.requireMember(projectId, userId);
        MemberLoadView view = workload.getMemberLoad(projectId, userId);
        ObjectNode data = json.createObjectNode();
        data.put("unassignedUncompletedTasks", view.unassignedUncompletedTasks());
        var members = data.putArray("members");
        for (MemberLoadView.MemberLoad member : view.members()) {
            ObjectNode item = members.addObject();
            item.put("userId", member.userId().toString());
            item.put("displayName", member.displayName());
            item.put("uncompletedTasks", member.uncompletedTasks());
            item.put("uncompletedEstimateHours", member.uncompletedEstimateHours().doubleValue());
            item.put("inProgressTasks", member.inProgressTasks());
            item.put("overdueTasks", member.overdueTasks());
            item.put("missingEstimateTasks", member.missingEstimateTasks());
        }
        data.put("fieldGuide", "members 是项目全体成员的当前负载快照（估算工时口径）。"
                + "建议把新任务分给 uncompletedEstimateHours 较低且无逾期的成员；视图不含容量数据，禁止宣称某人已超负荷。");
        return data;
    }
}
