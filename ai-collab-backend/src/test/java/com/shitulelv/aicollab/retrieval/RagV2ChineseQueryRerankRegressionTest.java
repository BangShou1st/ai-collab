package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.SemanticHeuristicReranker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG_V2_QUAL_EVAL-01 缺陷的正向回归测试（离线确定性，不依赖数据库与模型）。
 *
 * <p><b>历史缺陷证据（保留存档，勿删）</b>：修复前 {@code SemanticHeuristicReranker.extractTerms}
 * 以标点/空白切分查询词项，对不含标点的中文自然语言整句查询，整句成为单一超长 term，
 * {@code calculateContentScore}/{@code calculateHeadingScore} 几乎必然为 0，
 * combinedScore ≈ rrfScore × 0.35（约 0.006–0.04）；且 {@code RetrievalCandidate.toDocumentSearchHit()}
 * 把该重排分（或 RRF 分 ≈0.016）写入 {@code DocumentSearchHit.similarity}，
 * {@code KnowledgeContextBuilder.MIN_SIMILARITY = 0.15} 按余弦语义过滤后，
 * 生产 QA / Agent 管线对中文查询系统性 BELOW_SIMILARITY_THRESHOLD 全量丢弃证据并统一拒答。
 * 修复前本测试断言 {@code v2Context.sources()).isEmpty()}（缺陷特征固化），评测报告
 * {@code docs/archive/report/2026-11-07-rag-v2-quality-eval-final-report.md} HIGH-1。</p>
 *
 * <p><b>修复后的正确行为（本测试长期断言，防止同类缺陷再次出现）</b>：</p>
 * <ul>
 *   <li>类型化评分契约：similarity 只承载准入分数（max(Dense 余弦, Lexical 匹配强度)），
 *       RRF/重排分只承载排序语义并写入 {@code metadata.retrievalScores}。</li>
 *   <li>中文长句（无标点/带标点）经修复后的重排不再坍缩，目标证据进入最终上下文。</li>
 *   <li>排序语义保留：重排把标题/正文更匹配的目标块排到无关块之前。</li>
 *   <li>准入语义保留：真实低相关候选仍被拒绝，不因修复全面放行。</li>
 *   <li>证据身份不变：chunkId/documentId/content/contentHash 逐字保留。</li>
 * </ul>
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
    void naturalChineseSentenceQueryRetainsTargetEvidenceInV2Assembly() {
        List<DocumentSearchHit> v2Hits = rerankedProductionHits(NATURAL_QUERY, syntheticDenseHits());

        // 正向断言 1：准入分数回到可信召回分数语义——目标块 similarity = Dense 余弦 0.72，
        // 不再被 rerankScore/rrfScore（量级 0.006–0.016）冒充。
        DocumentSearchHit target = v2Hits.stream()
                .filter(hit -> hit.content().contains(MARKER)).findFirst().orElseThrow();
        assertThat(target.similarity())
                .as("准入分数必须等于 Dense 余弦相似度，不得被 RRF/重排分冒充")
                .isEqualTo(0.72);
        assertThat(target.similarity())
                .as("目标证据必须高于装配准入阈值（修复前缺陷特征：低于 0.15 全量丢弃）")
                .isGreaterThanOrEqualTo(KnowledgeContextBuilder.MIN_SIMILARITY);

        // 正向断言 2：类型化评分元数据齐全，RRF/重排分数只在元数据中承载排序语义。
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) target.metadata()
                .get(com.shitulelv.aicollab.common.retrieval.RetrievalScores.METADATA_KEY);
        assertThat(scores).isNotNull();
        assertThat(scores).containsKeys(
                com.shitulelv.aicollab.common.retrieval.RetrievalScores.DENSE_SIMILARITY,
                com.shitulelv.aicollab.common.retrieval.RetrievalScores.RRF_SCORE,
                com.shitulelv.aicollab.common.retrieval.RetrievalScores.RERANK_SCORE,
                com.shitulelv.aicollab.common.retrieval.RetrievalScores.ADMISSION_SCORE,
                com.shitulelv.aicollab.common.retrieval.RetrievalScores.ADMISSION_BASIS);

        // 正向断言 3：V2 装配不再丢失全部证据，含目标事实的块进入上下文。
        KnowledgeContext v2Context = BUILDER.buildBalanced(v2Hits);
        assertThat(v2Context.sources()).as("修复后中文整句查询不得再返回空上下文").isNotEmpty();
        assertThat(v2Context.sources().stream().anyMatch(s -> s.content().contains(MARKER)))
                .as("含目标事实的块必须进入最终上下文").isTrue();

        // 正向断言 4：排序语义保留——重排把标题/正文更匹配的目标块排到无关文档块之前。
        //（同一批候选复用，保证 chunkId 稳定。）
        List<DocumentSearchHit> reranked = rerankedProductionHits(NATURAL_QUERY, syntheticDenseHits());
        DocumentSearchHit rerankedTarget = reranked.stream()
                .filter(hit -> hit.content().contains(MARKER)).findFirst().orElseThrow();
        int targetRank = indexOfChunk(reranked, rerankedTarget.id());
        int irrelevantRank = indexOfChunk(reranked, reranked.stream()
                .filter(hit -> hit.contentHash().equals("hash-docb")).findFirst().orElseThrow().id());
        assertThat(targetRank).as("目标块排名必须优于无关文档块").isLessThan(irrelevantRank);

        // 正向断言 5：证据身份逐字保留。
        assertThat(target.id()).isEqualTo(target.id());
        assertThat(target.content()).contains(MARKER);
        assertThat(target.contentHash()).isEqualTo("hash-target");

        // 对照：带标点分句查询同样保留证据（修复前 maxEffectiveScore=0.0057 同样全丢）。
        KnowledgeContext punctuatedContext = BUILDER.buildBalanced(rerankedProductionHits(PUNCTUATED_QUERY, syntheticDenseHits()));
        assertThat(punctuatedContext.sources()).as("带标点中文查询同样不得空上下文").isNotEmpty();
        assertThat(punctuatedContext.sources().stream().anyMatch(s -> s.content().contains(MARKER))).isTrue();

        // 对照：V1 协议（dense 相似度直接装配）行为保持不变。
        KnowledgeContext v1Context = BUILDER.buildBalanced(syntheticDenseHits());
        assertThat(v1Context.sources()).as("V1 协议（dense 相似度直配）应保留证据").isNotEmpty();
        assertThat(v1Context.sources().stream().anyMatch(s -> s.content().contains(MARKER))).isTrue();
    }

    @Test
    void irrelevantLowAdmissionCandidatesAreStillRejectedAfterFix() {
        // 真实低相关候选（Dense 0.10，无有效 Lexical 证据）必须仍被准入规则拒绝。
        List<DocumentSearchHit> hits = new ArrayList<>(syntheticDenseHits());
        UUID docC = UUID.randomUUID();
        hits.add(new DocumentSearchHit(UUID.randomUUID(), docC, "G1-会议纪要- unrelated.md", "其他议题",
                "本周例会讨论了团建活动安排与办公室装修进度。", "hash-irrelevant", 0.10, Map.of()));

        List<DocumentSearchHit> converted = rerankedProductionHits(NATURAL_QUERY, hits);
        var built = BUILDER.buildBalancedWithDiagnostics(converted, KnowledgeContextBuilder.DEFAULT_MAX_SOURCES);

        assertThat(built.context().sources().stream()
                .anyMatch(s -> s.originalFilename().contains("unrelated")))
                .as("低相关候选不得因修复而混入上下文").isFalse();
        assertThat(built.dropReasons().values())
                .as("低相关候选的淘汰原因必须是准入阈值")
                .contains(com.shitulelv.aicollab.knowledge.domain.service.ContextDropReason.BELOW_SIMILARITY_THRESHOLD);
    }

    private static int indexOfChunk(List<DocumentSearchHit> hits, UUID chunkId) {
        for (int index = 0; index < hits.size(); index++) {
            if (hits.get(index).id().equals(chunkId)) return index;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 与生产一致的 V2 转换：Dense 召回候选 → RRF 融合分 → 启发式重排 → toDocumentSearchHit()。
     * 修复后转换产物的 similarity = 准入分数（Dense/Lexical），不再携带重排/RRF 分数。
     */
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
}
