package com.shitulelv.aicollab.planning.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.TaskView;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code query_project_tasks}：查询项目现有任务，让 planner 避免生成重复/冲突任务
 * （设计文档 §5.2）。复用 work 模块 {@link TaskApplicationService} 的查询口径。
 */
@Component
public class QueryProjectTasksPlanningTool implements PlanningReadOnlyTool {

    /** 单次返回任务数上限（有界查询，防止任务很多时结果挤爆 prompt）。 */
    static final int MAX_RETURNED = 50;

    private final TaskApplicationService tasks;
    private final ProjectAccessGuard access;
    private final ObjectMapper json;

    @Autowired
    public QueryProjectTasksPlanningTool(TaskApplicationService tasks, ProjectAccessGuard access,
                                         ObjectMapper json) {
        this.tasks = tasks;
        this.access = access;
        this.json = json;
    }

    @Override public String name() { return "query_project_tasks"; }

    @Override public String description() {
        return "查询项目现有任务（标题/状态/负责人/里程碑/日期/工时），用于避免生成重复或冲突的任务。"
                + "参数：status（可选，TODO/IN_PROGRESS/BLOCKED/DONE/CANCELED），limit（可选，1-50，默认 20）。";
    }

    @Override
    public JsonNode execute(JsonNode arguments, UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        TaskStatus status = null;
        if (arguments != null && arguments.hasNonNull("status")) {
            try {
                status = TaskStatus.valueOf(arguments.get("status").asText());
            } catch (IllegalArgumentException invalidStatus) {
                return json.createObjectNode().put("error", "INVALID_STATUS");
            }
        }
        int limit = 20;
        if (arguments != null && arguments.hasNonNull("limit")) {
            limit = Math.max(1, Math.min(MAX_RETURNED, arguments.get("limit").asInt(20)));
        }
        // work 模块查询口径：project 级列表（status/assignee/milestone 过滤在此只取 status，
        // planner 关心的是"已有哪些任务"，负责人与负载由 get_member_workload 承担）
        List<TaskView> matched = tasks.list(projectId, status, null, null, userId);
        ObjectNode data = json.createObjectNode();
        data.put("total", matched.size());
        data.put("returned", Math.min(limit, matched.size()));
        ArrayNode items = data.putArray("tasks");
        for (TaskView task : matched.stream().limit(limit).toList()) {
            ObjectNode item = items.addObject();
            item.put("id", task.id().toString());
            item.put("title", task.title());
            item.put("status", task.status().name());
            if (task.priority() != null) item.put("priority", task.priority().name());
            if (task.assigneeId() != null) item.put("assigneeId", task.assigneeId().toString());
            if (task.milestoneName() != null) item.put("milestone", task.milestoneName());
            if (task.startDate() != null) item.put("startDate", task.startDate().toString());
            if (task.dueDate() != null) item.put("dueDate", task.dueDate().toString());
            if (task.estimateHours() != null) item.put("estimateHours", task.estimateHours().doubleValue());
        }
        data.put("fieldGuide", "tasks 是项目当前真实任务记录，用于查重与冲突判断；total 超过 returned 时只核对了前 returned 条。");
        return data;
    }

    /** 单测可见的参数契约描述。 */
    public Set<String> knownArguments() { return Set.of("status", "limit"); }
}
