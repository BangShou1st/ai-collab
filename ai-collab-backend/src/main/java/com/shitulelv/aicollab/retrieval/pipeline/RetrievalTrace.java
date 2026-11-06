package com.shitulelv.aicollab.retrieval.pipeline;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 检索全链路追踪记录，用于性能监控与审查（不包含任何敏感凭据或密钥）。
 */
public record RetrievalTrace(
        long denseDurationMs,
        long lexicalDurationMs,
        long rerankDurationMs,
        long totalDurationMs,
        int denseRecallCount,
        int lexicalRecallCount,
        int fusedCount,
        int finalCount,
        boolean rerankerDegraded,
        String rerankerName,
        boolean sentenceWindowExpanded,
        boolean parentExpanded,
        Map<UUID, Double> rrfScores,
        List<DropRecord> dropReasons
) {
    public record DropRecord(UUID chunkId, String reason) {}
}
