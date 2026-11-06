package com.shitulelv.aicollab.retrieval.pipeline;

import java.util.List;
import java.util.UUID;

/**
 * 检索器抽象接口，支持向量检索、词项检索等多路检索实现。
 */
public interface Retriever {

    /**
     * 在指定项目与候选文档范围内执行检索。
     *
     * @param projectId   项目 ID
     * @param query       检索查询文本
     * @param documentIds 限定的文档 ID 列表，空列表或 null 表示不限定
     * @param topK        最大召回数
     * @return 检索候选列表，按各路原始得分降序排列
     */
    List<RetrievalCandidate> retrieve(UUID projectId, String query, List<UUID> documentIds, int topK);
}
