package com.shitulelv.aicollab.retrieval.pipeline;

/**
 * 检索结果重排器类型。
 */
public enum RerankerType {
    /**
     * 透传重排器，保持原 RRF 排序不变。
     */
    PASS_THROUGH,

    /**
     * 语义启发式重排器（标题匹配 + 核心词覆盖率 + RRF 融合）。
     */
    LEXICAL_HEURISTIC,

    /**
     * 神经网络真实模型重排器（基于大模型或语义评分接口）。
     */
    NEURAL_MODEL,

    /**
     * 管理员配置的 RERANK capability；默认 pipeline 不选择此类型。
     */
    CONFIGURED_RERANK
}
