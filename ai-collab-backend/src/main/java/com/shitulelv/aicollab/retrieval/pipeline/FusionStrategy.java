package com.shitulelv.aicollab.retrieval.pipeline;

import java.util.List;

/**
 * 多路检索召回结果融合策略接口。
 */
public interface FusionStrategy {

    /**
     * 将 Dense（向量检索）与 Lexical（词项检索）召回结果进行融合。
     *
     * @param denseCandidates   向量检索召回候选
     * @param lexicalCandidates 词项检索召回候选
     * @param topK              融合后保留的最大数量
     * @return 融合并稳定排序后的候选列表
     */
    List<RetrievalCandidate> fuse(List<RetrievalCandidate> denseCandidates,
                                  List<RetrievalCandidate> lexicalCandidates,
                                  int topK);
}
