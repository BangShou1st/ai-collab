package com.shitulelv.aicollab.common.retrieval;

import java.util.UUID;

/**
 * 由调用方传入的待核验种子证据引用。服务端在入库与装配前强制按项目与文档状态重验。
 */
public record SeedEvidenceInput(
        UUID documentId,
        Integer chunkNo,
        String contentHash,
        Double similarity,
        String origin,
        UUID bodySnapshotId) {
    public SeedEvidenceInput(UUID documentId, Integer chunkNo, String contentHash, Double similarity, String origin) {
        this(documentId, chunkNo, contentHash, similarity, origin, null);
    }

    public boolean hasIdentity() {
        return documentId != null && chunkNo != null && chunkNo >= 0
                && contentHash != null && contentHash.matches("[0-9a-fA-F]{64}");
    }
}
