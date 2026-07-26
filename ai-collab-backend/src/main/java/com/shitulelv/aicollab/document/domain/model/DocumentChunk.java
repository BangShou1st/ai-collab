package com.shitulelv.aicollab.document.domain.model;

public record DocumentChunk(int chunkNo, String heading, String content, String contentHash, int tokenEstimate) {
}
