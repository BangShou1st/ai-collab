package com.shitulelv.aicollab.common.retrieval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检索评分类型契约（RAG_V2_QUALITY_REGRESSION 修复的公共锚点）。
 *
 * <p>背景缺陷：{@code RetrievalCandidate.toDocumentSearchHit()} 曾把 RRF 融合分（量级
 * ≈1/(60+rank)≈0.016）或重排分直接写入 {@code DocumentSearchHit.similarity}，而上下文装配层
 * 以余弦相似度语义的 0.15 阈值过滤该字段，导致真实证据被 BELOW_SIMILARITY_THRESHOLD 全量丢弃。
 *
 * <p>修复后的契约：
 * <ul>
 *   <li><b>准入分数（admission score）</b>：只在两类可信召回分数之间取最大值——
 *       Dense 余弦相似度（[0,1]）与 Lexical 匹配强度（pg_trgm similarity / 精确包含校准值，[0,1]）。
 *       缺失的一路按“无该证据”处理（不贡献最大值），缺失的 Dense 分数绝不当作 0 分事实。
 *       RRF 融合分与重排分是<b>排序语义</b>，严禁进入准入分数。</li>
 *   <li><b>排序语义</b>：管线输出候选的顺序即最终排名（RRF → 重排）；下游装配与来源选择
 *       不得按准入分数重新排序。通过 {@link #METADATA_KEY} 识别“携带类型化排名语义”的管线输出。</li>
 *   <li><b>防伪造</b>：该元数据仅由内部检索管线写入，文档上传内容无法伪造准入状态；
 *       Lexical-only 候选不伪装 Dense 分数，仅有 Dense 分数的候选也不借用 Lexical 分数。</li>
 * </ul>
 */
public final class RetrievalScores {

    /** 管线输出命中在 metadata 中携带类型化评分的唯一键（嵌套 Map，JSON 可序列化）。 */
    public static final String METADATA_KEY = "retrievalScores";

    public static final String DENSE_SIMILARITY = "denseSimilarity";
    public static final String LEXICAL_SCORE = "lexicalScore";
    public static final String RRF_SCORE = "rrfScore";
    public static final String RERANK_SCORE = "rerankScore";
    public static final String RERANKER = "reranker";
    public static final String ADMISSION_SCORE = "admissionScore";
    public static final String ADMISSION_BASIS = "admissionBasis";

    public static final String BASIS_DENSE = "DENSE";
    public static final String BASIS_LEXICAL = "LEXICAL";
    public static final String BASIS_DENSE_AND_LEXICAL = "DENSE+LEXICAL";
    public static final String BASIS_NONE = "NONE";

    private RetrievalScores() {
    }

    /** 判定一个命中是否携带管线类型化评分（即排序语义来自 RRF/Reranker 而非 Dense 相似度）。 */
    public static boolean carriesTypedRanking(Map<String, Object> metadata) {
        return metadata != null && metadata.get(METADATA_KEY) instanceof Map<?, ?>;
    }

    /**
     * 构建类型化评分元数据。仅由检索管线内部调用；分数缺失时不写入对应键，
     * 保证“缺失”与“0 分”语义可区分。
     */
    public static Map<String, Object> of(
            Double denseSimilarity,
            Double lexicalScore,
            Double rrfScore,
            Double rerankScore,
            String rerankerName,
            double admissionScore,
            String admissionBasis) {
        Map<String, Object> scores = new LinkedHashMap<>();
        if (denseSimilarity != null) scores.put(DENSE_SIMILARITY, denseSimilarity);
        if (lexicalScore != null) scores.put(LEXICAL_SCORE, lexicalScore);
        if (rrfScore != null) scores.put(RRF_SCORE, rrfScore);
        if (rerankScore != null) scores.put(RERANK_SCORE, rerankScore);
        if (rerankerName != null && !rerankerName.isBlank()) scores.put(RERANKER, rerankerName);
        scores.put(ADMISSION_SCORE, admissionScore);
        scores.put(ADMISSION_BASIS, admissionBasis);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(METADATA_KEY, scores);
        return wrapper;
    }
}
