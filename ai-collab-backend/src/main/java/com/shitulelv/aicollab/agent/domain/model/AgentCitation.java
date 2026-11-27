package com.shitulelv.aicollab.agent.domain.model;

import java.util.UUID;

public record AgentCitation(
        UUID documentId,
        UUID chunkId,
        String filename,
        String heading,
        Integer pageNumber,
        String quote,
        double similarity,
        Integer chunkNo,
        String contentHash,
        UUID bodySnapshotId) {
    public AgentCitation(UUID documentId, UUID chunkId, String filename, String heading, Integer pageNumber,
            String quote, double similarity) {
        this(documentId, chunkId, filename, heading, pageNumber, quote, similarity, null, null, null);
    }
}
