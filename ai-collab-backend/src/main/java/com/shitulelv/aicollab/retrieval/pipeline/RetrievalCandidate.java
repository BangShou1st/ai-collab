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

    public DocumentSearchHit toDocumentSearchHit() {
        double effectiveScore = rerankScore != null ? rerankScore : (rrfScore > 0 ? rrfScore : (denseSimilarity != null ? denseSimilarity : 0.0));
        return new DocumentSearchHit(
                id,
                documentId,
                originalFilename,
                heading,
                content,
                contentHash,
                effectiveScore,
                metadata
        );
    }
}
