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
        return sb.toString();
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
