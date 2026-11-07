package com.shitulelv.aicollab.retrieval.query;

/**
 * 检索执行策略（与问题复杂度分离的第二个独立维度，RAG_V2_GEMINI_ADAPTIVE_RETRIEVAL-01 阶段 3）。
 *
 * <p>证据依据：公开 Benchmark（DuRetrieval 官方全量语料）实测 Dense-only 全面占优
 * （Recall@10 0.809 vs Hybrid RRF 0.729，P50 465ms vs 3434ms），且项目内部 P9 冻结基线
 * （QA 52/54）本身即以 dense 直连协议测得——普通单一意图问题以 Dense 优先有双重证据支撑；
 * 但企业长文档场景词法/父子扩展仍可能必要，故 Dense 优先策略必须携带有界回退，
 * 不得无条件放弃完整管线。</p>
 */
public enum RetrievalStrategy {
    /** 仅 Dense 向量检索（无词法、无窗口/父子扩展）。最快路径。 */
    DENSE,
    /** Dense + Lexical + RRF（完整默认管线，含扩展与重排）。 */
    HYBRID,
    /** 多查询分解 Hybrid（原始查询 + 子查询 RRF 融合）。 */
    MULTI_QUERY_HYBRID,
    /** Dense 优先 + 有界回退：证据不足（装配后无来源）时回退完整 HYBRID。 */
    DENSE_WITH_BOUNDED_FALLBACK
}
