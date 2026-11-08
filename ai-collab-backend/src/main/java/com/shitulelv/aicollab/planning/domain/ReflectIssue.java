package com.shitulelv.aicollab.planning.domain;

import java.util.List;

/**
 * REFLECT 反思阶段的一条问题（§5.3 独立 schema，不走 ValidationIssueCatalog 的 issue 码体系）。
 *
 * @param severity      HARD / SOFT
 * @param targetType    MILESTONE / TASK / PLAN（草案级问题）
 * @param targetTempKey 目标实体 tempKey；targetType=PLAN 时为 null
 * @param field         字段定位（如 dueDate、dependencyTempKeys）；可空
 * @param suggestion    模型给出的修复建议（进入修复 prompt 与版本 JSON，供前端展示）
 */
public record ReflectIssue(
        ReflectIssueSeverity severity,
        String targetType,
        String targetTempKey,
        String field,
        String suggestion) {
}
