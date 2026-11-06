package com.shitulelv.aicollab.document.application.view;

import java.util.Map;
import java.util.UUID;

/**
 * 文档检索命中视图。
 *
 * <p>{@code similarity} 字段语义（准入分数，见
 * {@code com.shitulelv.aicollab.common.retrieval.RetrievalScores} 契约）：
 * <ul>
 *   <li>旧版 Dense 直连路径：pgvector 余弦相似度，[0,1]。</li>
 *   <li>Lexical SQL 路径：pg_trgm similarity 与精确包含校准值（标题 0.8 / 正文 0.6）的较大者，[0,1]。</li>
 *   <li>V2 混合管线输出：max(denseSimilarity, lexicalScore)，即两类可信召回分数的较大者；
 *       RRF 融合分（≈1/(60+rank)）与重排分是排序语义，严禁写入本字段冒充准入分数。
 *       管线输出的命中额外在 {@code metadata.retrievalScores} 携带全部分数的类型化来源。</li>
 * </ul>
 */
public record DocumentSearchHit(
        UUID id,
        UUID documentId,
        String originalFilename,
        String heading,
        String content,
        String contentHash,
        double similarity,
        Map<String, Object> metadata) {

    public DocumentSearchHit(
            UUID id, UUID documentId, String originalFilename,
            String heading, String content, String contentHash, double similarity) {
        this(id, documentId, originalFilename, heading, content, contentHash, similarity, Map.of());
    }
}
