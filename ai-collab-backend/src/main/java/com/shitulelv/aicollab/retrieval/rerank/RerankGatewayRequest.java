package com.shitulelv.aicollab.retrieval.rerank;

import java.util.List;
import java.util.UUID;

/**
 * 发往 Python {@code POST /v1/rag/rerank} 的调用参数（冻结契约 §1）。
 *
 * @param projectId 项目作用域，仅用于幂等键与 callId（检索层可拿不到时传 null）
 * @param query     原始查询
 * @param topK      期望的 topK（1–50）
 * @param candidates 已裁剪为合法文本的候选（1–50 条，id 唯一）
 * @param deadlineMs 服务端硬 deadline（1–30000）
 */
public record RerankGatewayRequest(
        UUID projectId,
        String query,
        int topK,
        List<Candidate> candidates,
        int deadlineMs) {

    public RerankGatewayRequest {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 注意：只放 worker 契约允许的字段，不携带任何 Java 内部 id / 元数据。 */
    public record Candidate(String id, String title, String text) {
    }
}
