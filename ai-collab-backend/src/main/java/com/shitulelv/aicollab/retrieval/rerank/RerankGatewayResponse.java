package com.shitulelv.aicollab.retrieval.rerank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python {@code POST /v1/rag/rerank} 的解析结果。
 *
 * <p>{@code scores} 只覆盖 worker 实际返回的 id；缺失 id 由 {@link RerankModeRouter}
 * 用 Java 启发式分数补齐并记 {@code PARTIAL_SCORE}，不在这里静默填 0。</p>
 */
public record RerankGatewayResponse(
        int schemaVersion,
        Map<String, Double> scores,
        String modelAlias,
        Usage usage) {

    public RerankGatewayResponse {
        scores = scores == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(scores));
        usage = usage == null ? Usage.unknown() : usage;
    }

    /** usage 缺失时 {@code source=UNKNOWN}、token 为 null，不用 0 冒充"已知"。 */
    public record Usage(Integer inputTokens, Integer outputTokens, String source) {
        public static Usage unknown() {
            return new Usage(null, null, "UNKNOWN");
        }
    }
}
