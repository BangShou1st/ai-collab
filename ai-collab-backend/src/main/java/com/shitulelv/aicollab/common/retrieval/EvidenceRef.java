package com.shitulelv.aicollab.common.retrieval;

import java.util.UUID;

/**
 * 跨模块共享的最小可信证据引用契约（Knowledge / Agent / Planning 统一身份）。
 */
public record EvidenceRef(
        UUID projectId,
        UUID documentId,
        UUID chunkId,
        int chunkNo,
        UUID bodySnapshotId,
        String contentHash,
        String filename,
        String heading,
        String excerpt,
        double similarity,
        String sourceOrigin) {
    public SeedEvidenceInput asSeed() {
        return new SeedEvidenceInput(documentId, chunkNo, contentHash, similarity, sourceOrigin, bodySnapshotId);
    }
}
