package com.shitulelv.aicollab.planning.draft;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Python {@code POST /v1/planning/draft} 的解析结果（W5 冻结 §1）。
 *
 * <p>{@code plan} 保留为 {@link JsonNode}：Java 侧复用 {@link PlanningDraftParser}
 * 把它转换为 {@code TaskPlanDraft} 后<b>重跑</b> {@code TaskPlanDraftValidator}，
 * 不直接把 Python 结果当作可用草稿。{@code usage} 缺失时 {@code source=UNKNOWN}、
 * token 为 null，不用 0 冒充“已知”。</p>
 */
public record PlanningDraftResponse(
        int schemaVersion,
        JsonNode plan,
        List<String> warnings,
        Usage usage) {

    public PlanningDraftResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        usage = usage == null ? Usage.unknown() : usage;
    }

    /** usage 缺失 → UNKNOWN/null。 */
    public record Usage(Integer inputTokens, Integer outputTokens, String source) {
        public static Usage unknown() {
            return new Usage(null, null, "UNKNOWN");
        }
    }
}
