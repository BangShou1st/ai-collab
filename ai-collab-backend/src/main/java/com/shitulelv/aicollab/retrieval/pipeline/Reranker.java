package com.shitulelv.aicollab.retrieval.pipeline;

import java.util.List;

/**
 * 检索结果重排器接口。
 *
 * <p>核心契约：重排器严禁修改、伪造或删除原始证据内容及来源身份
 * （包括 chunkId, documentId, heading, content, contentHash 及 metadata）。
 * 当重排器发生异常或超时时，必须安全降级返回原候选，并标记 rerankerDegraded。
 */
public interface Reranker {

    /**
     * 重排器唯一标识名称。
     */
    String name();

    /**
     * 对候选结果根据与 query 的相关性进行二次重排。
     *
     * @param query      查询文本
     * @param candidates 待重排的候选列表（通常为 RRF 融合后的列表）
     * @param topN       最终保留的最大候选数
     * @return 重排并截断后的候选列表
     */
    List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN);
}
