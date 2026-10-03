package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.TaskView;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class TaskListAgentTool implements AgentTool {
    private final TaskApplicationService tasks;
    private final ObjectMapper json;

    public TaskListAgentTool(TaskApplicationService tasks, ObjectMapper json) {
        this.tasks = tasks;
        this.json = json;
    }

    @Override public String name() { return "list_tasks"; }
    @Override public boolean writesBusinessData() { return false; }

    @Override public com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition definition() {
        return com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition.fromJson(name(),
                "搜索项目任务。按 ID 稳定排序，用 nextCursor 续页；同名任务必须展示候选 ID/负责人/日期并请用户选择，不猜 ID。每页是当前事实快照，并发新增可能出现在后续查询中。", """
                {"type":"object","additionalProperties":false,"properties":{
                  "query":{"type":"string","maxLength":200},"status":{"type":"string","enum":["TODO","IN_PROGRESS","BLOCKED","DONE","CANCELED"]},
                  "assigneeId":{"type":"string","format":"uuid"},"milestoneId":{"type":"string","format":"uuid"},
                  "cursor":{"type":"string","format":"uuid"},"limit":{"type":"integer","minimum":1,"maximum":50}}}
                """,false);
    }

    @Override
    public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
        AgentToolArguments.requireFields(
                arguments, Set.of("status", "assigneeId", "milestoneId", "limit", "query", "cursor"));
        String statusText = AgentToolArguments.text(arguments, "status", 30, false);
        TaskStatus status;
        try {
            status = statusText == null ? null : TaskStatus.valueOf(statusText);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("status 不是合法任务状态");
        }
        UUID assignee = AgentToolArguments.uuid(arguments, "assigneeId", false);
        UUID milestone = AgentToolArguments.uuid(arguments, "milestoneId", false);
        int limit = AgentToolArguments.integer(arguments, "limit", 20, 1, 50);
        String query = AgentToolArguments.text(arguments,"query",200,false);
        UUID cursor = AgentToolArguments.uuid(arguments,"cursor",false);
        List<TaskView> values = tasks.list(
                context.projectId(), status, assignee, milestone, context.userId()).stream()
                .filter(t -> query == null || (t.title()+" "+t.description()).toLowerCase(java.util.Locale.ROOT)
                        .contains(query.toLowerCase(java.util.Locale.ROOT)))
                .sorted(java.util.Comparator.comparing(t -> t.id().toString())).toList();
        List<TaskView> page = values.stream().filter(t -> cursor == null || t.id().toString().compareTo(cursor.toString())>0).toList();
        ArrayNode items = json.createArrayNode();
        page.stream().limit(limit).map(this::narrow).forEach(items::add);
        ObjectNode data = json.createObjectNode();
        data.set("items", items);
        data.put("fieldGuide", "items 是本次查询的真实任务记录。title=标题，status=任务状态（TODO待处理、IN_PROGRESS进行中、BLOCKED已阻塞、DONE已完成、CANCELED已取消），assigneeId/assigneeName=正式负责人，null 表示未分配。外层 status=SUCCEEDED 仅表示工具调用成功，不是任务状态。按用户要求回答已有字段，不因 Skill 模板要求额外分析而宣称已有字段缺失。");
        ArrayNode facts = data.putArray("taskFacts");
        for (JsonNode item : items) {
            ObjectNode fact = facts.addObject();
            for (String field : List.of("id", "title", "status", "assigneeId", "assigneeName")) fact.set(field, item.path(field));
        }
        data.put("returned", items.size());
        data.put("total", values.size());
        data.put("hasMore", page.size()>limit);
        data.put("truncated", page.size()>limit);
        if(page.size()>limit) data.put("nextCursor",page.get(limit-1).id().toString()); else data.putNull("nextCursor");
        data.put("sort","id ASC");
        data.put("consistency","LIVE_KEYSET");
        return new AgentToolResult(data, List.of(), List.of());
    }

    private ObjectNode narrow(TaskView task) {
        ObjectNode value = json.createObjectNode();
        value.put("id", task.id().toString());
        value.put("title", task.title());
        value.put("description", truncate(task.description(), 800));
        value.put("status", task.status().name());
        value.put("priority", task.priority().name());
        put(value, "milestoneId", task.milestoneId());
        put(value, "assigneeId", task.assigneeId());
        value.put("assigneeName", task.assigneeDisplayName());
        value.put("startDate", task.startDate() == null ? null : task.startDate().toString());
        value.put("dueDate", task.dueDate() == null ? null : task.dueDate().toString());
        value.put("version", task.version());
        value.put("unfinishedDependencyCount", task.unfinishedDependencyCount());
        value.set("dependencyIds", json.valueToTree(task.dependencyIds()));
        return value;
    }

    static String truncate(String value, int maximum) {
        if (value == null) return "";
        int count = value.codePointCount(0, value.length());
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    static void put(ObjectNode node, String name, UUID value) {
        if (value == null) node.putNull(name); else node.put(name, value.toString());
    }
}
