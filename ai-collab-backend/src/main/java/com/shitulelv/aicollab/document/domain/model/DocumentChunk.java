package com.shitulelv.aicollab.document.domain.model;

import java.util.Map;
import java.util.UUID;

public record DocumentChunk(
        UUID id,
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
                         Map<String, Object> metadata, UUID parentChunkId, String chunkType) {
        this(null, chunkNo, heading, content, contentHash, tokenEstimate, metadata, parentChunkId, chunkType);
    }

    public DocumentChunk(int chunkNo, String heading, String content, String contentHash, int tokenEstimate,
                         Map<String, Object> metadata) {
        this(null, chunkNo, heading, content, contentHash, tokenEstimate, metadata, null, TYPE_STANDARD);
    }

    public DocumentChunk(int chunkNo, String heading, String content, String contentHash, int tokenEstimate) {
        this(null, chunkNo, heading, content, contentHash, tokenEstimate, Map.of(), null, TYPE_STANDARD);
    }

    public DocumentChunk withParent(UUID parentId, String type) {
        return new DocumentChunk(id, chunkNo, heading, content, contentHash, tokenEstimate, metadata, parentId, type);
    }

    public DocumentChunk withId(UUID id) {
        return new DocumentChunk(id, chunkNo, heading, content, contentHash, tokenEstimate, metadata, parentChunkId, chunkType);
    }
}
