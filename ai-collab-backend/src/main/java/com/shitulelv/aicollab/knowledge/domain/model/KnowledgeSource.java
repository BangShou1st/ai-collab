package com.shitulelv.aicollab.knowledge.domain.model;

import java.util.Map;
import java.util.UUID;

public record KnowledgeSource(
        UUID chunkId,
        UUID documentId,
        String originalFilename,
        String heading,
        String content,
        String contentHash,
        double similarity,
        int rank,
        Map<String, Object> metadata) {

    public KnowledgeSource(
            UUID chunkId, UUID documentId, String originalFilename,
            String heading, String content, String contentHash,
            double similarity, int rank) {
        this(chunkId, documentId, originalFilename, heading, content, contentHash, similarity, rank, Map.of());
    }

    public Integer chunkNo() {
        return metadata != null && metadata.get("chunkNo") instanceof Number number
                ? number.intValue() : null;
    }

    public UUID bodySnapshotId() {
        return metadata != null && metadata.get("bodySnapshotId") instanceof UUID snapshot
                ? snapshot : null;
    }

    public Integer pageNumber() {
        if (metadata == null || !metadata.containsKey("pageNumber")) return null;
        Object val = metadata.get("pageNumber");
        if (val instanceof Number n) return n.intValue();
        return null;
    }
}
