package com.shitulelv.aicollab.retrieval.rerank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python {@code POST /v1/rag/rerank} 的解析结果。
 *
 * <p>{@code scores} 只覆盖 worker 实际返回的 id；缺失 id 由 {@link RerankModeRouter}
 * 用 Java 启发式分数补齐并记 {@code PARTIAL_SCORE}，不在这里静默填 0。</p>
 *
 * <p>{@code modelLatencyMs} 是 worker 响应里可选的模型调用耗时（W3.1 可观测扩展，
 * 不在 W3 冻结契约 §1 的必填字段内）：{@code null} 表示 worker 未提供/非法，调用方必须
 * 按 {@code UNKNOWN} 处理，不得填 0 冒充已知；负数在解析阶段已被归一为 {@code null}。</p>
 */
public record RerankGatewayResponse(
        int schemaVersion,
        Map<String, Double> scores,
        String modelAlias,
        Usage usage,
        Integer modelLatencyMs) {

    public RerankGatewayResponse {
        scores = scores == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(scores));
        usage = usage == null ? Usage.unknown() : usage;
        // 防御性归一：模型耗时只能是"已知的非负整数"或"未知"。
        if (modelLatencyMs != null && modelLatencyMs < 0) {
            modelLatencyMs = null;
        }
    }

    /** usage 缺失时 {@code source=UNKNOWN}、token 为 null，不用 0 冒充"已知"。 */
    public record Usage(Integer inputTokens, Integer outputTokens, String source) {
        public static Usage unknown() {
            return new Usage(null, null, "UNKNOWN");
        }
    }
}
