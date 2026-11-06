package com.shitulelv.aicollab.retrieval.evidence;

import java.util.Map;
import java.util.UUID;

/**
 * 不可变证据契约：
 * 具有严密的来源身份、版本标记与位置元数据。
 * 重排器及融合管线严禁篡改或伪造正文及身份信息。
 */
public record EvidenceRecord(
        UUID chunkId,
        UUID documentId,
        Integer documentVersion,
        String originalFilename,
        String heading,
        String content,
        String contentHash,
        Integer offsetStart,
        Integer offsetEnd,
        EvidenceCoverageStatus coverageStatus,
        Map<String, Object> metadata) {

    public EvidenceRecord {
        if (chunkId == null) throw new IllegalArgumentException("chunkId 不能为空");
        if (documentId == null) throw new IllegalArgumentException("documentId 不能为空");
        if (content == null) throw new IllegalArgumentException("content 不能为空");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
