package com.shitulelv.aicollab.planning.application;

import java.util.Map;

/**
 * REFLECT 反思阶段问题的只读视图（设计文档 §5.3）。
 * 数据来源：版本行 validation_result_json 顶层的 issues 数组（ReflectIssueModelOutput 落库形态），
 * 与 ValidationIssueCatalog 的结构化 issue（TaskPlanValidationIssueView）是两套体系，互不混用。
 */
public record TaskPlanReflectIssueView(
        String severity,
        String targetType,
        String targetTempKey,
        String field,
        String suggestion
) {
    /** 宽松解析：畸形条目（severity 非法/缺关键字段）直接丢弃，不阻塞详情展示。 */
    static TaskPlanReflectIssueView fromMap(Map<?, ?> raw) {
        Object severity = raw.get("severity");
        if (severity == null) return null;
        String s = severity.toString();
        if (!"HARD".equals(s) && !"SOFT".equals(s)) return null;
        Object targetType = raw.get("targetType");
        if (targetType == null) return null;
        return new TaskPlanReflectIssueView(
                s,
                targetType.toString(),
                str(raw.get("targetTempKey")),
                str(raw.get("field")),
                str(raw.get("suggestion")));
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
}
