package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.SemanticHeuristicReranker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG_V2_QUAL_EVAL-01 缺陷最小复现（离线确定性，不依赖数据库与模型）。
 *
 * <p><b>缺陷</b>：{@code SemanticHeuristicReranker.extractTerms} 以标点/空白切分查询词项，
 * 对不含标点的中文自然语言整句查询，整句成为单一超长 term，导致
 * {@code calculateContentScore}/{@code calculateHeadingScore} 几乎必然为 0，
 * combinedScore ≈ rrfScore × 0.35（约 0.01–0.04），低于
 * {@code KnowledgeContextBuilder.MIN_SIMILARITY = 0.15}，
 * 最终候选在上下文装配层被 BELOW_SIMILARITY_THRESHOLD 全量丢弃，
 * 生产 QA / Agent 管线对无标点中文查询系统性返回空上下文并拒答。</p>
 *
 * <p>本测试按当前生产代码行为如实固化证据（不修改生产代码、不断言"正确"行为），
 * 作为 RAG_V2_QUALITY_REGRESSION 裁决的最小复现资产。带标点查询作为对照组。</p>
 */
class RagV2ChineseQueryRerankRegressionTest {

    private static final KnowledgeContextBuilder BUILDER = new KnowledgeContextBuilder();
    private static final SemanticHeuristicReranker RERANKER = new SemanticHeuristicReranker();

    private static final String MARKER = "住宿费标准为每晚五百元";
    private static final String CONTENT = "差旅费管理办法（V2）：第十条 员工出差入住一线城市酒店，" + MARKER
            + "。超过标准的部分原则上由个人承担，确因接待需要经审批可据实报销。";

    /** 与 P9 冻结集一致的无标点中文自然语言查询（G04 维度）。 */
    private static final String NATURAL_QUERY = "现在出差到一线城市的住宿费标准是每晚多少钱";
    /** 对照组：同一意图带标点/分句的查询。 */
    private static final String PUNCTUATED_QUERY = "住宿费标准是每晚多少钱？出差到一线城市怎么报销？";

    @Test
    void naturalChineseSentenceQueryLosesAllEvidenceInV2Assembly() {
        List<DocumentSearchHit> denseHits = syntheticDenseHits();

        // V2 生产路径：RetrievalPipeline 产物经 toDocumentSearchHit() 转换后 similarity=有效分（rerankScore）
        double maxNatural = maxEffectiveScore(NATURAL_QUERY, denseHits);
        double maxPunctuated = maxEffectiveScore(PUNCTUATED_QUERY, denseHits);
        System.out.println("RAG_DEFECT_EVIDENCE naturalQuery='" + NATURAL_QUERY + "' maxEffectiveScore=" + maxNatural);
        System.out.println("RAG_DEFECT_EVIDENCE punctuatedQuery='" + PUNCTUATED_QUERY + "' maxEffectiveScore=" + maxPunctuated);
        System.out.println("RAG_DEFECT_EVIDENCE minSimilarityThreshold=" + KnowledgeContextBuilder.MIN_SIMILARITY);

        // 缺陷特征 1：无标点整句查询的最高有效分远低于装配阈值（rrf×0.35 量级）
        assertThat(maxNatural).as("无标点中文整句查询的最高有效分应低于装配阈值（缺陷特征）")
                .isLessThan(KnowledgeContextBuilder.MIN_SIMILARITY);
        // 缺陷特征 2：带标点分句查询同样低于阈值——子句仍长于原文逐字匹配窗口，缺陷面比"无标点"更广
        assertThat(maxPunctuated).as("带标点分句查询的最高有效分同样低于装配阈值（缺陷面更广）")
                .isLessThan(KnowledgeContextBuilder.MIN_SIMILARITY);

        // 缺陷特征 3：V2 装配（转换后候选）丢失全部证据 → 生产恒拒答
        KnowledgeContext v2Context = BUILDER.buildBalanced(convertToProductionHits(NATURAL_QUERY, denseHits));
        System.out.println("RAG_DEFECT_EVIDENCE v2AssemblyRetainedSources=" + v2Context.sources().size());
        assertThat(v2Context.sources()).as("V2 装配应丢失全部证据（缺陷特征：生产将恒拒答）").isEmpty();

        // 对照：同一批候选走 V1 协议（dense 相似度直接装配，无重排/无 RRF 分数替换）正常保留
        KnowledgeContext v1Context = BUILDER.buildBalanced(denseHits);
        System.out.println("RAG_DEFECT_EVIDENCE v1ProtocolRetainedSources=" + v1Context.sources().size());
        assertThat(v1Context.sources()).as("V1 协议（dense 相似度直配）应保留证据").isNotEmpty();
        assertThat(v1Context.sources().stream().anyMatch(s -> s.content().contains(MARKER)))
                .as("V1 协议应保留含目标事实的块").isTrue();
    }

    /** 与生产一致的候选转换：toDocumentSearchHit() 的 similarity = rerankScore ?? rrfScore ?? denseSimilarity。 */
    private static double maxEffectiveScore(String query, List<DocumentSearchHit> denseHits) {
        return rerankedProductionHits(query, denseHits).stream()
                .mapToDouble(DocumentSearchHit::similarity).max().orElse(0d);
    }

    private static List<DocumentSearchHit> convertToProductionHits(String query, List<DocumentSearchHit> denseHits) {
        return rerankedProductionHits(query, denseHits);
    }

    /** 走真实启发式重排并转换为生产 DocumentSearchHit。 */
    private static List<DocumentSearchHit> rerankedProductionHits(String query, List<DocumentSearchHit> denseHits) {
        List<RetrievalCandidate> fused = new ArrayList<>();
        for (int index = 0; index < denseHits.size(); index++) {
            DocumentSearchHit hit = denseHits.get(index);
            fused.add(new RetrievalCandidate(hit.id(), hit.documentId(), hit.originalFilename(), hit.heading(),
                    hit.content(), hit.contentHash(), hit.metadata(), index + 1, hit.similarity(),
                    null, null, 1.0 / (60 + index + 1), null, null));
        }
        List<RetrievalCandidate> reranked = RERANKER.rerank(query, fused, 10);
        return reranked.stream().map(RetrievalCandidate::toDocumentSearchHit).toList();
    }

    /** 构造与真实 pgvector 召回同构的候选：目标块 + 同文档干扰块 + 他文档块，dense 相似度均高于装配阈值。 */
    private static List<DocumentSearchHit> syntheticDenseHits() {
        List<DocumentSearchHit> hits = new ArrayList<>();
        UUID docA = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        hits.add(new DocumentSearchHit(target, docA, "D2-差旅费管理办法V2.md", "第十条 住宿费标准",
                CONTENT, "hash-target", 0.72, Map.of()));
        hits.add(new DocumentSearchHit(UUID.randomUUID(), docA, "D2-差旅费管理办法V2.md", "第十一条 报销流程",
                "员工返回后十五个工作日内提交报销申请，附审批单与发票原件。财务复核后按标准支付。",
                "hash-other", 0.65, Map.of()));
        UUID docB = UUID.randomUUID();
        hits.add(new DocumentSearchHit(UUID.randomUUID(), docB, "C2-资产盘点操作规范-华南中心.md", "一、盘点组织",
                "华南中心固定资产盘点每年开展一次，盘点基准日为十二月三十一日。数据冻结时长一般为五个工作日。",
                "hash-docb", 0.58, Map.of()));
        return hits;
    }

    /** 走真实启发式重排，返回与输入顺序对应的 rerankScore（等价于生产 effectiveScore）。 */
    private static double[] rerankScores(String query, List<DocumentSearchHit> denseHits) {
        List<RetrievalCandidate> fused = new ArrayList<>();
        for (int index = 0; index < denseHits.size(); index++) {
            DocumentSearchHit hit = denseHits.get(index);
            fused.add(new RetrievalCandidate(hit.id(), hit.documentId(), hit.originalFilename(), hit.heading(),
                    hit.content(), hit.contentHash(), hit.metadata(), index + 1, hit.similarity(),
                    null, null, 1.0 / (60 + index + 1), null, null));
        }
        List<RetrievalCandidate> reranked = RERANKER.rerank(query, fused, 10);
        double[] scores = new double[denseHits.size()];
        for (int index = 0; index < denseHits.size(); index++) {
            UUID id = denseHits.get(index).id();
            scores[index] = reranked.stream().filter(c -> c.id().equals(id)).findFirst()
                    .map(c -> c.toDocumentSearchHit().similarity()).orElse(0d);
        }
        return scores;
    }
}
