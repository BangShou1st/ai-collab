package com.shitulelv.aicollab.document.application.view;

import java.util.Map;
import java.util.UUID;

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
