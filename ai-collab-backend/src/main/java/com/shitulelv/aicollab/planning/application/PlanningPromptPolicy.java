package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
import com.shitulelv.aicollab.planning.domain.ValidationIssueCatalog;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 4: Centralized prompt generation from domain rules and context.
 *
 * Generates model-executable rules from:
 * - ValidationIssueCatalog (severity, repairable fields)
 * - Plan date range
 * - Max task count
 * - Member whitelist
 * - Source whitelist
 *
 * MUST NOT expose:
 * - Java class names, database column names, SQL, exception stacks
 * - API keys, authorization headers
 * - Full prompt content in events or issues
 */
@Component
public class PlanningPromptPolicy {

    /**
     * Generate skeleton rules for the model.
     * Skeleton only outputs identity fields — no detail fields.
     */
    public String skeletonRules(PlanningContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("<BUSINESS_RULES>\n");
        sb.append("- 只输出骨架身份字段：tempKey, title, objective, targetDate, sortOrder。\n");
        sb.append("- 不要输出 description, priority, estimatedHours, startDate, dueDate, suggestedAssigneeId, dependencyTempKeys, sourceRefs。\n");
        sb.append("- 里程碑不超过 8 个。\n");
        sb.append("- 任务不超过 ").append(context.maxTaskCount()).append(" 个。\n");
        sb.append("- tempKey 全局唯一。\n");
        sb.append("- 任务必须引用已存在的里程碑 tempKey。\n");
        sb.append("- 不得捏造成员、来源或项目事实。\n");
        sb.append("</BUSINESS_RULES>\n");
        sb.append(currentVersusHistoryRules());
        return sb.toString();
    }

    /**
     * Generate detail rules for the model.
     * Detail补全所有业务字段，但不得修改骨架身份。
     */
    public String detailRules(PlanningContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("<PLAN_CONSTRAINTS>\n");
        sb.append("planStartDate=").append(context.planStartDate()).append("\n");
        sb.append("planDueDate=").append(context.planDueDate()).append("\n");
        sb.append("maxTaskCount=").append(context.maxTaskCount()).append("\n");
        sb.append("</PLAN_CONSTRAINTS>\n\n");

        sb.append("<MEMBER_CONTEXT>\n");
        sb.append("仅允许使用当前项目成员 UUID：\n");
        for (UUID memberId : context.memberIds()) {
            sb.append("- ").append(memberId).append("\n");
        }
        sb.append("不确定负责人时输出 null。\n");
        sb.append("</MEMBER_CONTEXT>\n\n");

        sb.append("<SOURCES>\n");
        sb.append("仅允许使用当前来源：\n");
        for (String ref : context.sourceRefs()) {
            sb.append("- ").append(ref).append("\n");
        }
        sb.append("无文档依据时 sourceRefs=[]。\n");
        sb.append("</SOURCES>\n\n");

        sb.append("<SKELETON_IDENTITY>\n");
        sb.append("只读，不得修改 tempKey, title, objective, targetDate, sortOrder, summary, assumptions, risks。\n");
        sb.append("</SKELETON_IDENTITY>\n\n");

        sb.append("<BUSINESS_RULES>\n");
        sb.append("- 不得捏造成员、来源或项目事实。\n");
        sb.append("- 不确定负责人时输出 null。\n");
        sb.append("- 无文档依据时 sourceRefs=[]。\n");
        sb.append("- 每个 Skeleton milestone/task 必须且只能对应一个 Detail。\n");
        sb.append("- 不得输出或修改身份字段。\n");
        sb.append("- priority 只能是 LOW/MEDIUM/HIGH/URGENT。\n");
        sb.append("- estimatedHours 非空时 >= 0.5 且 <= 80。\n");
        sb.append("- 日期非空时位于规划范围。\n");
        sb.append("- startDate <= dueDate。\n");
        sb.append("- 前置任务 dueDate <= 后续任务 startDate。\n");
        sb.append("- 依赖不得重复、自依赖或引用不存在任务。\n");
        sb.append("</BUSINESS_RULES>\n");
        sb.append(currentVersusHistoryRules());
        sb.append(sourceAuthorityRules());
        return sb.toString();
    }

    /**
     * 当前约束与历史变更的区分规则：
     * PLAN_INPUT 中的"约束"文本是会话历史，可能包含已被取代的旧值（如多次调整后的数量）；
     * 当前必须执行的只有 PLAN_CONSTRAINTS 中的结构化字段（maxTaskCount/日期）。
     * 历史变更过程只能作为背景提及，不得写成仍须执行的当前要求。
     */
    private String currentVersusHistoryRules() {
        return """
                <CURRENT_VS_HISTORY>
                - PLAN_INPUT 中的"约束"文本是用户约束的完整历史，可能同时包含已被后续修改取代的旧值。
                - 当前唯一生效的结构化约束是 PLAN_CONSTRAINTS 中的 planStartDate、planDueDate 和 maxTaskCount；两者冲突时以 PLAN_CONSTRAINTS 为准。
                - 任务数量、日期范围、里程碑数必须按 PLAN_CONSTRAINTS 执行；约束历史中出现过的其他数值只代表曾经提出过，不是当前要求。
                - 验收标准/任务描述不得要求已被取代的历史数值在最终草稿中生效；如需说明数量变更过程，必须明确标注为"历史变更"，不得写成当前验收条件。
                - 本规则不禁止描述历史；禁止的是把被取代的历史值当作当前必须保持的条件。
                </CURRENT_VS_HISTORY>

                """;
    }

    /**
     * 来源权威规则：SOURCES 是本次服务端实际检索到的资料片段；
     * Agent 会话中"未取得/未读到"的旧结论不能覆盖实际存在的来源内容。
     */
    private String sourceAuthorityRules() {
        return """
                <SOURCE_AUTHORITY>
                - SOURCES 是本次服务端实际检索并选定的资料片段；其中实际出现的内容就是本次可用资料。
                - 规划请求或会话历史中"资料未取得/未读到"的说法是当时的读取边界，不能覆盖 SOURCES 中实际存在的内容；引用 S 编号来源时以片段实际内容为准。
                - SOURCES 未覆盖的主题不得捏造来源；无文档依据时 sourceRefs=[]。
                </SOURCE_AUTHORITY>

                """;
    }

    /**
     * Generate repair rules for the model.
     * Only allows patching specific fields for specific issues.
     */
    public String repairRules(List<StructuredValidationIssue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("<VALIDATION_ISSUES>\n");
        for (StructuredValidationIssue issue : issues) {
            sb.append("- code=").append(issue.code()).append("\n");
            sb.append("  severity=").append(issue.severity()).append("\n");
            sb.append("  targetType=").append(issue.targetType()).append("\n");
            if (issue.targetTempKey() != null) sb.append("  targetTempKey=").append(issue.targetTempKey()).append("\n");
            if (issue.field() != null) sb.append("  field=").append(issue.field()).append("\n");
            if (issue.relatedTempKey() != null) sb.append("  relatedTempKey=").append(issue.relatedTempKey()).append("\n");
            for (var entry : issue.safeDetails().entrySet()) {
                sb.append("  ").append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
        }
        sb.append("</VALIDATION_ISSUES>\n\n");

        // Collect all allowed fields from repairable issues
        Set<String> allowedFields = issues.stream()
                .map(i -> ValidationIssueCatalog.repairableFields(i.code()))
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        sb.append("<ALLOWED_CHANGES>\n");
        for (String field : allowedFields) {
            sb.append("- ").append(field).append("\n");
        }
        sb.append("</ALLOWED_CHANGES>\n\n");

        sb.append("<LOCKED_FIELDS>\n");
        sb.append("- tempKey\n");
        sb.append("- milestoneTempKey\n");
        sb.append("- title\n");
        sb.append("- objective\n");
        sb.append("- sortOrder\n");
        sb.append("- summary\n");
        sb.append("- assumptions\n");
        sb.append("- risks\n");
        sb.append("</LOCKED_FIELDS>\n\n");

        sb.append("只输出 patch JSON。只修改 ALLOWED_CHANGES 中列出的字段。不得修改 LOCKED_FIELDS。\n");
        return sb.toString();
    }

    /**
     * Planning context for prompt generation.
     */
    public record PlanningContext(
            LocalDate planStartDate,
            LocalDate planDueDate,
            int maxTaskCount,
            Set<UUID> memberIds,
            Set<String> sourceRefs
    ) {}
}
