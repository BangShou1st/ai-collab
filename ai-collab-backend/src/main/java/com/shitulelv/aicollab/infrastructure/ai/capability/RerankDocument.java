package com.shitulelv.aicollab.infrastructure.ai.capability;

/** 单次 rerank 的候选输入；id 只用于响应映射，正文不写日志。 */
public record RerankDocument(String id, String title, String text) {
}
