package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * RERANK capability 的冻结 settings 视图。
 *
 * <p>只包含冻结件 §2.2 的字段；不含 chat/embedding 字段，也不含模型文件路径。</p>
 */
public record RerankSettings(
        String protocol,
        int timeoutMs,
        int maxCandidates,
        int batchSize,
        int maxLength,
        String scoreType,
        int topK,
        String healthPath,
        String fallback) {

    public ObjectNode toJson(ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("protocol", protocol);
        node.put("timeoutMs", timeoutMs);
        node.put("maxCandidates", maxCandidates);
        node.put("batchSize", batchSize);
        node.put("maxLength", maxLength);
        node.put("scoreType", scoreType);
        node.put("topK", topK);
        node.put("healthPath", healthPath);
        node.put("fallback", fallback);
        return node;
    }
}
