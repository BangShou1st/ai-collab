package com.shitulelv.aicollab.planning.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.planning.domain.PlanMilestone;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Python planning draft 的最小解析器（W5 冻结 §2 “解析为 TaskPlanDraft”）。
 *
 * <p>只做 <b>类型/结构</b> 解析：字段缺失、多余或类型非法 → {@link InvalidDraftException}，
 * 由影子服务记 {@code schema_invalid}；<b>业务校验一律交给</b>
 * {@code TaskPlanDraftValidator.assess(...)} 重跑，不在本类复制任何规则。</p>
 *
 * <p>契约差异映射：Python 的 {@code assigneeId} 映射到 Java 的 {@code suggestedAssigneeId}
 * （AI 生成草稿不允许直接携带 {@code assigneeId}，否则 Validator 会判
 * {@code AI_GENERATED_ASSIGNEE_NOT_ALLOWED}）。Python 响应没有 summary/assumptions/risks/
 * sources：summary 用调用方给出的有界回退文本，其余为空列表；缺少任务的 description 保持空串，
 * 让 Validator 以 {@code TASK_TEXT_INVALID} 记录真实问题，而不是在解析阶段吞掉。</p>
 *
 * <p>本类不写库、不发事件、不持有任何仓储。</p>
 */
public final class PlanningDraftParser {

    private static final int MAX_SUMMARY_CHARS = 2000;
    private static final int MAX_MILESTONES = 200;
    private static final int MAX_TASKS = 500;
    private static final Set<String> PLAN_FIELDS = Set.of("milestones", "tasks");
    private static final Set<String> MILESTONE_FIELDS = Set.of(
            "tempKey", "title", "objective", "description", "targetDate", "sortOrder", "sourceRefs");
    private static final Set<String> TASK_FIELDS = Set.of(
            "tempKey", "milestoneTempKey", "title", "objective", "description", "priority",
            "estimatedHours", "startDate", "dueDate", "assigneeId", "suggestedAssigneeId",
            "dependencyTempKeys", "sourceRefs", "sortOrder");

    public TaskPlanDraft parse(JsonNode plan, String fallbackSummary) {
        if (plan == null || !plan.isObject()) {
            throw new InvalidDraftException("plan 不是 JSON 对象");
        }
        requireOnlyFields(plan, PLAN_FIELDS, "plan");
        JsonNode milestonesNode = plan.get("milestones");
        JsonNode tasksNode = plan.get("tasks");
        if (milestonesNode == null || !milestonesNode.isArray()) {
            throw new InvalidDraftException("plan.milestones 不是数组");
        }
        if (tasksNode == null || !tasksNode.isArray()) {
            throw new InvalidDraftException("plan.tasks 不是数组");
        }
        if (milestonesNode.size() > MAX_MILESTONES || tasksNode.size() > MAX_TASKS) {
            throw new InvalidDraftException("plan 数量超过解析上限");
        }

        List<PlanMilestone> milestones = new ArrayList<>(milestonesNode.size());
        for (int index = 0; index < milestonesNode.size(); index++) {
            milestones.add(milestone(milestonesNode.get(index), index));
        }
        List<PlanTask> tasks = new ArrayList<>(tasksNode.size());
        for (int index = 0; index < tasksNode.size(); index++) {
            tasks.add(task(tasksNode.get(index), index));
        }
        return new TaskPlanDraft(summary(fallbackSummary), List.of(), List.of(), milestones, tasks,
                List.of());
    }

    private static String summary(String fallback) {
        String value = fallback == null ? "" : fallback.strip();
        if (value.isEmpty()) {
            value = "Python planning draft";
        }
        return value.length() <= MAX_SUMMARY_CHARS ? value : value.substring(0, MAX_SUMMARY_CHARS);
    }

    private static PlanMilestone milestone(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            throw new InvalidDraftException("plan.milestones[" + index + "] 不是对象");
        }
        requireOnlyFields(node, MILESTONE_FIELDS, "plan.milestones[" + index + "]");
        return new PlanMilestone(
                requiredText(node, "tempKey", index),
                requiredText(node, "title", index),
                requiredText(node, "objective", index),
                optionalText(node, "description"),
                optionalDate(node, "targetDate"),
                optionalInt(node, "sortOrder", index),
                optionalStringArray(node, "sourceRefs"));
    }

    private static PlanTask task(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            throw new InvalidDraftException("plan.tasks[" + index + "] 不是对象");
        }
        requireOnlyFields(node, TASK_FIELDS, "plan.tasks[" + index + "]");
        UUID suggestedAssigneeId = optionalUuid(node, "assigneeId");
        if (suggestedAssigneeId == null) {
            suggestedAssigneeId = optionalUuid(node, "suggestedAssigneeId");
        }
        return new PlanTask(
                requiredText(node, "tempKey", index),
                requiredText(node, "milestoneTempKey", index),
                requiredText(node, "title", index),
                requiredText(node, "objective", index),
                optionalText(node, "description"),
                optionalText(node, "priority"),
                optionalDecimal(node, "estimatedHours"),
                optionalDate(node, "startDate"),
                optionalDate(node, "dueDate"),
                suggestedAssigneeId,
                null,
                optionalStringArray(node, "dependencyTempKeys"),
                optionalStringArray(node, "sourceRefs"),
                optionalInt(node, "sortOrder", index));
    }

    private static String requiredText(JsonNode node, String field, int index) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidDraftException("第 " + index + " 项缺少字符串字段 " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidDraftException("字段 " + field + " 不是字符串");
        }
        return value.asText();
    }

    private static int optionalInt(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber()) {
            throw new InvalidDraftException("字段 " + field + " 不是整数");
        }
        return value.asInt();
    }

    private static BigDecimal optionalDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new InvalidDraftException("字段 " + field + " 不是数字");
        }
        return value.decimalValue();
    }

    private static LocalDate optionalDate(JsonNode node, String field) {
        String raw = optionalText(node, field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException invalid) {
            throw new InvalidDraftException("字段 " + field + " 不是 ISO 日期");
        }
    }

    private static UUID optionalUuid(JsonNode node, String field) {
        String raw = optionalText(node, field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException invalid) {
            throw new InvalidDraftException("字段 " + field + " 不是 UUID");
        }
    }

    private static List<String> optionalStringArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new InvalidDraftException("字段 " + field + " 不是数组");
        }
        List<String> items = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (item == null || !item.isTextual()) {
                throw new InvalidDraftException("字段 " + field + " 含非字符串元素");
            }
            items.add(item.asText());
        }
        return List.copyOf(items);
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String path) {
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new InvalidDraftException(path + " 含未知字段 " + name);
            }
        }
    }

    /** Python 草稿的结构/类型非法（业务规则仍由 Validator 判定）。 */
    public static final class InvalidDraftException extends RuntimeException {
        public InvalidDraftException(String message) {
            super(message);
        }
    }
}
