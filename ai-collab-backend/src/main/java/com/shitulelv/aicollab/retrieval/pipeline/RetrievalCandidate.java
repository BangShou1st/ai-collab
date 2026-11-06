package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record RetrievalCandidate(
        UUID id,
        UUID documentId,
        String originalFilename,
        String heading,
        String content,
        String contentHash,
        Map<String, Object> metadata,
        Integer denseRank,
        Double denseSimilarity,
        Integer lexicalRank,
        Double lexicalScore,
        Double rrfScore,
        Double rerankScore,
        String rerankerName) {

    public RetrievalCandidate {
        metadata = metadata != null ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata)) : Map.of();
    }

    public static RetrievalCandidate fromDenseHit(DocumentSearchHit hit, int rank) {
        return new RetrievalCandidate(
                hit.id(),
                hit.documentId(),
                hit.originalFilename(),
                hit.heading(),
                hit.content(),
                hit.contentHash(),
                hit.metadata() != null ? hit.metadata() : Map.of(),
                rank,
                hit.similarity(),
                null,
                null,
                0.0,
                null,
                null
        );
    }

    public static RetrievalCandidate fromLexicalHit(DocumentSearchHit hit, int rank) {
        return new RetrievalCandidate(
                hit.id(),
                hit.documentId(),
                hit.originalFilename(),
                hit.heading(),
                hit.content(),
                hit.contentHash(),
                hit.metadata() != null ? hit.metadata() : Map.of(),
                null,
                null,
                rank,
                hit.similarity(),
                0.0,
                null,
                null
        );
    }

    public RetrievalCandidate withFusion(Integer dRank, Double dSim, Integer lRank, Double lScore, double score) {
        return new RetrievalCandidate(
                id, documentId, originalFilename, heading, content, contentHash, metadata,
                dRank, dSim, lRank, lScore, score, rerankScore, rerankerName
        );
    }

    public RetrievalCandidate withRerank(double score, String reranker) {
        return new RetrievalCandidate(
                id, documentId, originalFilename, heading, content, contentHash, metadata,
                denseRank, denseSimilarity, lexicalRank, lexicalScore, rrfScore, score, reranker
        );
    }

    public RetrievalCandidate withMetadata(Map<String, Object> extraMetadata) {
        if (extraMetadata == null || extraMetadata.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(this.metadata);
        merged.putAll(extraMetadata);
        return new RetrievalCandidate(
                id, documentId, originalFilename, heading, content, contentHash, merged,
                denseRank, denseSimilarity, lexicalRank, lexicalScore, rrfScore, rerankScore, rerankerName
        );
    }

    public String rerankSource() {
        return rerankerName;
    }

    public String expandedContent() {
        Object val = metadata.get("expandedContent");
        return val instanceof String s ? s : null;
    }

    public String parentContext() {
        Object val = metadata.get("parentContext");
        return val instanceof String s ? s : null;
    }

    public String effectiveContent() {
        String expanded = expandedContent();
        if (expanded != null && !expanded.isBlank()) {
            return expanded;
        }
        return content;
    }

    /**
     * 准入分数（类型化评分契约，见 {@link com.shitulelv.aicollab.common.retrieval.RetrievalScores}）：
     * 只在两类可信召回分数——Dense 余弦相似度与 Lexical 匹配强度——之间取最大值；
     * 缺失的一路不贡献最大值（缺失 ≠ 0 分事实），RRF 融合分与重排分是排序语义，绝不冒充准入分数。
     */
    public double admissionScore() {
        double dense = denseSimilarity != null ? denseSimilarity : Double.NEGATIVE_INFINITY;
        double lexical = lexicalScore != null ? lexicalScore : Double.NEGATIVE_INFINITY;
        if (dense == Double.NEGATIVE_INFINITY && lexical == Double.NEGATIVE_INFINITY) {
            return 0.0;
        }
        return Math.max(dense, lexical);
    }

    private String admissionBasis() {
        boolean hasDense = denseSimilarity != null;
        boolean hasLexical = lexicalScore != null;
        if (hasDense && hasLexical) {
            return com.shitulelv.aicollab.common.retrieval.RetrievalScores.BASIS_DENSE_AND_LEXICAL;
        }
        if (hasDense) {
            return com.shitulelv.aicollab.common.retrieval.RetrievalScores.BASIS_DENSE;
        }
        if (hasLexical) {
            return com.shitulelv.aicollab.common.retrieval.RetrievalScores.BASIS_LEXICAL;
        }
        return com.shitulelv.aicollab.common.retrieval.RetrievalScores.BASIS_NONE;
    }

    public DocumentSearchHit toDocumentSearchHit() {
        double admission = admissionScore();
        Map<String, Object> typedMetadata = new LinkedHashMap<>(metadata);
        typedMetadata.putAll(com.shitulelv.aicollab.common.retrieval.RetrievalScores.of(
                denseSimilarity, lexicalScore, rrfScore, rerankScore, rerankerName,
                admission, admissionBasis()));
        return new DocumentSearchHit(
                id,
                documentId,
                originalFilename,
                heading,
                content,
                contentHash,
                admission,
                Collections.unmodifiableMap(typedMetadata)
        );
    }
}
