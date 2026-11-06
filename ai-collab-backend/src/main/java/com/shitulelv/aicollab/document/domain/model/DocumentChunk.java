package com.shitulelv.aicollab.document.domain.model;

import java.util.Map;
import java.util.UUID;

public record DocumentChunk(
        int chunkNo,
        String heading,
        String content,
        String contentHash,
        int tokenEstimate,
        Map<String, Object> metadata,
        UUID parentChunkId,
        String chunkType) {

    public static final String TYPE_STANDARD = "STANDARD";
    public static final String TYPE_PARENT = "PARENT";
    public static final String TYPE_CHILD = "CHILD";

    public DocumentChunk(int chunkNo, String heading, String content, String contentHash, int tokenEstimate,
                         Map<String, Object> metadata) {
        this(chunkNo, heading, content, contentHash, tokenEstimate, metadata, null, TYPE_STANDARD);
    }

    public DocumentChunk(int chunkNo, String heading, String content, String contentHash, int tokenEstimate) {
        this(chunkNo, heading, content, contentHash, tokenEstimate, Map.of(), null, TYPE_STANDARD);
    }

    public DocumentChunk withParent(UUID parentId, String type) {
        return new DocumentChunk(chunkNo, heading, content, contentHash, tokenEstimate, metadata, parentId, type);
    }
}
