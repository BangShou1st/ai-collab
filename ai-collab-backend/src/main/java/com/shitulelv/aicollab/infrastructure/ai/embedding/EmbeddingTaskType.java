package com.shitulelv.aicollab.infrastructure.ai.embedding;

/** 嵌入任务类型：Gemini 等模型对 document/query 使用不同任务指令，二者必须成对使用（同一向量空间契约）。 */
public enum EmbeddingTaskType {
    /** 文档索引嵌入（Gemini: RETRIEVAL_DOCUMENT）。索引链路默认语义。 */
    DOCUMENT,
    /** 检索查询嵌入（Gemini: RETRIEVAL_QUERY）。查询链路必须显式使用。 */
    QUERY
}
