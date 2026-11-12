package com.shitulelv.aicollab.infrastructure.ai.capability;

import java.util.List;

/**
 * Adapter 的统一结果。失败不抛异常：调用方必须能区分失败并回退 Java 启发式。
 *
 * @param modelLatencyMs HTTP 往返耗时；调用未发生时可为 null
 * @param totalMs       Adapter 总耗时
 * @param overheadMs    max(0, total - model)
 */
public record RerankCallResult(
        boolean success,
        String outcome,
        String reason,
        List<RerankScore> scores,
        Integer modelLatencyMs,
        long totalMs,
        long overheadMs,
        int candidateCount,
        int topK) {

    public RerankCallResult {
        scores = scores == null ? List.of() : List.copyOf(scores);
    }

    public static RerankCallResult success(List<RerankScore> scores, Integer modelLatencyMs,
            long totalMs, int candidateCount, int topK) {
        long overhead = modelLatencyMs == null ? totalMs : Math.max(0L, totalMs - modelLatencyMs);
        return new RerankCallResult(true, "SUCCESS", null, scores, modelLatencyMs, totalMs,
                overhead, candidateCount, topK);
    }

    public static RerankCallResult failure(String outcome, String reason, Integer modelLatencyMs,
            long totalMs, int candidateCount, int topK) {
        long overhead = modelLatencyMs == null ? totalMs : Math.max(0L, totalMs - modelLatencyMs);
        return new RerankCallResult(false, outcome, reason, List.of(), modelLatencyMs, totalMs,
                overhead, candidateCount, topK);
    }
}
