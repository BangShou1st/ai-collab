package com.shitulelv.aicollab.planning.domain;

import java.util.List;

/**
 * REFLECT 反思阶段的严格输出契约（对应 orchestrator 的 REFLECT_SCHEMA）。
 * 由 {@code TaskPlanOutputParser.parseReflect} 用 FAIL_ON_UNKNOWN_PROPERTIES 严格解析，
 * 模型输出任何多余字段都会被判为畸形输出（触发降级，不失败整个计划）。
 */
public record ReflectModelOutput(List<ReflectIssue> issues) {
    public ReflectModelOutput {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
