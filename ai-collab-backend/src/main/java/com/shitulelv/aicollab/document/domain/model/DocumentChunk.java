package com.shitulelv.aicollab.document.domain.model;

import java.util.Map;

public record DocumentChunk(
        int chunkNo,
        String heading,
        String content,
        String contentHash,
        int tokenEstimate,
        Map<String, Object> metadata) {

    public DocumentChunk(int chunkNo, String heading, String content, String contentHash, int tokenEstimate) {
        this(chunkNo, heading, content, contentHash, tokenEstimate, Map.of());
    }
}
