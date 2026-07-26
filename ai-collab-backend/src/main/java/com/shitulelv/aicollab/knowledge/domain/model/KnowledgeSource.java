package com.shitulelv.aicollab.knowledge.domain.model;

import java.util.UUID;

public record KnowledgeSource(
        UUID chunkId,
        UUID documentId,
        String originalFilename,
        String heading,
        String content,
        String contentHash,
        double similarity,
        int rank) {
}
