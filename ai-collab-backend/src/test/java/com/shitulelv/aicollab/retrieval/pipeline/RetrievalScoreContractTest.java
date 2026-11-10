package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.common.retrieval.RetrievalScores;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.service.ContextDropReason;
import com.shitulelv.aicollab.knowledge.domain.service.CrossDocumentSourceSelector;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG V2 检索评分类型契约专项测试（离线确定性）。
 *
 * <p>修复背景：{@code toDocumentSearchHit()} 曾把 RRF 分（≈0.016）或重排分写入
 * {@code DocumentSearchHit.similarity}，与装配层 0.15 余弦阈值发生跨分数体系混淆，
 * 导致 39 题中 33 题空上下文。本测试固化修复后的类型化契约：
 * 准入分数只由 Dense 余弦 / Lexical 匹配强度承载，RRF/重排分只承载排序语义。</p>
 */
class RetrievalScoreContractTest {

    private static final KnowledgeContextBuilder BUILDER = new KnowledgeContextBuilder();
    private static final CrossDocumentSourceSelector SELECTOR = new CrossDocumentSourceSelector();
    private static final ReciprocalRankFusion FUSION = new ReciprocalRankFusion();
    private static final SemanticHeuristicReranker RERANKER = new SemanticHeuristicReranker();

    private static final double THRESHOLD = KnowledgeContextBuilder.MIN_SIMILARITY;

    // ==================== 候选构造工具 ====================

    /** 全字段构造：与 RRF 融合后的候选形态一致。 */
    private static RetrievalCandidate candidate(UUID chunkId, UUID documentId, String content, String hash,
                                                Integer denseRank, Double denseSimilarity,
                                                Integer lexicalRank, Double lexicalScore,
                                                Double rrfScore) {
        return new RetrievalCandidate(chunkId, documentId, "文档.md", "标题", content, hash,
                Map.of(), denseRank, denseSimilarity, lexicalRank, lexicalScore, rrfScore, null, null);
    }

    private static RetrievalCandidate denseOnly(UUID chunkId, UUID documentId, String content,
                                                String hash, double similarity) {
        return candidate(chunkId, documentId, content, hash, 1, similarity, null, null, 1.0 / 61);
    }

    private static RetrievalCandidate lexicalOnly(UUID chunkId, UUID documentId, String content,
                                                  String hash, double lexicalScore) {
        return candidate(chunkId, documentId, content, hash, null, null, 1, lexicalScore, 1.0 / 61);
    }

    private static DocumentSearchHit convert(RetrievalCandidate candidate) {
        return candidate.toDocumentSearchHit();
    }

    private static List<DocumentSearchHit> convertAll(RetrievalCandidate... candidates) {
        List<DocumentSearchHit> hits = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            hits.add(convert(candidate));
        }
        return hits;
    }

    // ==================== 场景矩阵 ====================

    @Test
    void denseCandidateWithRrfScoreKeepsCosineAsAdmissionScore() {
        // Dense 0.82 + RRF 0.016：可正确保留，且 similarity 必须是 0.82 而非 0.016。
        RetrievalCandidate candidate = candidate(
                UUID.randomUUID(), UUID.randomUUID(), "含目标事实的正文", "h1", 1, 0.82, null, null, 0.016);
        DocumentSearchHit hit = convert(candidate);
        assertThat(hit.similarity()).isEqualTo(0.82);
        var built = BUILDER.buildWithDiagnostics(new ArrayList<>(List.of(hit)), 5);
        assertThat(built.context().sources()).hasSize(1);
        assertThat(built.dropReasons()).isEmpty();
    }

    @Test
    void lowDenseWithoutLexicalEvidenceIsRejected() {
        // Dense 0.10，无有效 Lexical 证据：按原准入规则拒绝。
        RetrievalCandidate candidate = denseOnly(
                UUID.randomUUID(), UUID.randomUUID(), "低相关正文", "h2", 0.10);
        var built = BUILDER.buildWithDiagnostics(new ArrayList<>(List.of(convert(candidate))), 5);
        assertThat(built.context().sources()).isEmpty();
        assertThat(built.dropReasons().values())
                .containsExactly(ContextDropReason.BELOW_SIMILARITY_THRESHOLD);
    }

    @Test
    void lexicalOnlyExactHitIsAdmittedWithIndependentRule() {
        // Dense 缺失，Lexical 精确命中（正文包含校准 0.6）：可依独立规则保留，不伪造 Dense 分数。
        RetrievalCandidate candidate = lexicalOnly(
                UUID.randomUUID(), UUID.randomUUID(), "包含查询精确词项的正文", "h3", 0.6);
        DocumentSearchHit hit = convert(candidate);
        assertThat(hit.similarity()).isEqualTo(0.6);
        var built = BUILDER.buildWithDiagnostics(new ArrayList<>(List.of(hit)), 5);
        assertThat(built.context().sources()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) hit.metadata().get(RetrievalScores.METADATA_KEY);
        assertThat(scores.get(RetrievalScores.ADMISSION_BASIS)).isEqualTo(RetrievalScores.BASIS_LEXICAL);
        assertThat(scores).doesNotContainKey(RetrievalScores.DENSE_SIMILARITY);
    }

    @Test
    void denseAndLexicalBothHitFusesWithoutDuplication() {
        // Dense + Lexical 同时命中：正确融合（单候选、双路贡献），准入分取 max 且不重复保留。
        UUID chunkId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        List<RetrievalCandidate> dense = List.of(
                candidate(chunkId, documentId, "双路命中正文", "h4", 2, 0.55, null, null, 0.0));
        List<RetrievalCandidate> lexical = List.of(
                candidate(chunkId, documentId, "双路命中正文", "h4", null, null, 1, 0.6, 0.0));
        List<RetrievalCandidate> fused = FUSION.fuse(dense, lexical, 20);
        assertThat(fused).hasSize(1);
        RetrievalCandidate merged = fused.getFirst();
        assertThat(merged.denseSimilarity()).isEqualTo(0.55);
        assertThat(merged.lexicalScore()).isEqualTo(0.6);
        assertThat(merged.rrfScore()).isCloseTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-12));
        DocumentSearchHit hit = convert(merged);
        assertThat(hit.similarity()).isEqualTo(0.6);
        var built = BUILDER.buildWithDiagnostics(new ArrayList<>(List.of(hit)), 5);
        assertThat(built.context().sources()).hasSize(1);
    }

    @Test
    void lowDenseWithStrongLexicalHitIsAdmittedByExplicitCombinationRule() {
        // Dense 0.10 低分 + Lexical 0.6 有效命中：按明确的 max 组合规则判定保留。
        RetrievalCandidate candidate = candidate(
                UUID.randomUUID(), UUID.randomUUID(), "向量弱但词项精确命中的正文", "h5",
                3, 0.10, 1, 0.6, 0.02);
        DocumentSearchHit hit = convert(candidate);
        assertThat(hit.similarity()).isEqualTo(0.6);
        var built = BUILDER.buildWithDiagnostics(new ArrayList<>(List.of(hit)), 5);
        assertThat(built.context().sources()).hasSize(1);
    }

    @Test
    void rrfScoreAloneCannotBypassAdmission() {
        // 只有 RRF 分数、没有可信召回证据（合成候选）：不得自动绕过准入。
        RetrievalCandidate candidate = candidate(
                UUID.randomUUID(), UUID.randomUUID(), "仅有融合分数的候选", "h6",
                null, null, null, null, 0.033);
        DocumentSearchHit hit = convert(candidate);
        assertThat(hit.similarity()).isLessThan(THRESHOLD);
        var built = BUILDER.buildWithDiagnostics(new ArrayList<>(List.of(hit)), 5);
        assertThat(built.context().sources()).isEmpty();
        assertThat(built.dropReasons().values())
                .containsExactly(ContextDropReason.BELOW_SIMILARITY_THRESHOLD);
    }

    @Test
    void tinyRerankScoreIsNeverTreatedAsCosine() {
        // Reranker 给出非常小的分数（中文长句重排分坍缩场景）：不得被当成余弦过滤。
        RetrievalCandidate candidate = denseOnly(
                UUID.randomUUID(), UUID.randomUUID(), "与查询高度相关的正文内容", "h7", 0.70);
        List<RetrievalCandidate> reranked = RERANKER.rerank("完全无关的极长中文查询没有共同词项", List.of(candidate), 10);
        DocumentSearchHit hit = convert(reranked.getFirst());
        assertThat(hit.similarity()).as("准入分数不受重排分影响").isEqualTo(0.70);
        var built = BUILDER.buildWithDiagnostics(new ArrayList<>(List.of(hit)), 5);
        assertThat(built.context().sources()).hasSize(1);
    }

    @Test
    void crossDocumentIdenticalContentKeepsBothSourceIdentities() {
        // 多文档同正文（同 hash、不同文档）：保留不同来源身份，不错误合并。
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        List<DocumentSearchHit> hits = convertAll(
                lexicalOnly(UUID.randomUUID(), docA, "两份文档完全相同的条款文本", "same-hash", 0.6),
                lexicalOnly(UUID.randomUUID(), docB, "两份文档完全相同的条款文本", "same-hash", 0.55));
        var context = BUILDER.buildDiverse(hits, 12);
        assertThat(context.sources()).extracting(com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource::documentId)
                .containsExactlyInAnyOrder(docA, docB);
    }

    @Test
    void sameDocumentDuplicateEvidenceIsStillDeduplicated() {
        // 同文档重复证据：继续去重。
        UUID documentId = UUID.randomUUID();
        List<DocumentSearchHit> hits = convertAll(
                lexicalOnly(UUID.randomUUID(), documentId, "同一条证据", "dup", 0.6),
                lexicalOnly(UUID.randomUUID(), documentId, "同一条证据（同文档副本）", "dup", 0.5),
                lexicalOnly(UUID.randomUUID(), documentId, "另一条证据", "other", 0.55));
        var built = BUILDER.buildWithDiagnostics(new ArrayList<>(hits), 12);
        assertThat(built.context().sources()).hasSize(2);
        assertThat(built.dropReasons().get(hits.get(1).id()))
                .isEqualTo(ContextDropReason.INTRA_DOCUMENT_DUPLICATE);
    }

    @Test
    void irrelevantCorpusIsNotMassAdmittedAfterFix() {
        // 无关语料：不得因修复产生全面放行。
        List<DocumentSearchHit> hits = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            hits.add(convert(denseOnly(UUID.randomUUID(), UUID.randomUUID(), "无关语料 " + i, "noise-" + i, 0.05)));
        }
        var built = BUILDER.buildWithDiagnostics(hits, 5);
        assertThat(built.context().sources()).isEmpty();
    }

    // ==================== 装配入口一致性 ====================

    @Test
    void allAssemblyEntriesShareAdmissionSemanticsForLexicalOnlyCandidates() {
        // buildWithTokenBudget / buildBalancedWithDiagnostics / buildWithDiagnostics /
        // buildDiverseWithDiagnostics 对 Lexical-only 候选共享同一准入语义。
        UUID chunkId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        List<DocumentSearchHit> hits = new ArrayList<>(List.of(convert(lexicalOnly(
                chunkId, documentId, "词项精确命中的有效证据正文，长度足以写入提示词。", "h8", 0.6))));

        var tokenBudgetBuilt = BUILDER.buildWithTokenBudget(hits, 5, 2500, null, null, null);
        assertThat(tokenBudgetBuilt.context().sources()).hasSize(1);
        assertThat(tokenBudgetBuilt.dropReasons()).isEmpty();

        var balancedBuilt = BUILDER.buildBalancedWithDiagnostics(hits, 5);
        assertThat(balancedBuilt.context().sources()).hasSize(1);

        var legacyBuilt = BUILDER.buildWithDiagnostics(hits, 5);
        assertThat(legacyBuilt.context().sources()).hasSize(1);

        var diverseBuilt = BUILDER.buildDiverseWithDiagnostics(hits, 5);
        assertThat(diverseBuilt.context().sources()).hasSize(1);
        assertThat(diverseBuilt.context().sources().getFirst().chunkId()).isEqualTo(chunkId);
    }

    @Test
    void budgetStopClassificationKeepsAdmissionReasonForLateCandidates() {
        // buildWithDiagnostics 预算停止后的 classifyCandidatesAfterBudgetStop 也保留准入语义：
        // 低分候选标阈值原因而非预算原因。
        UUID documentId = UUID.randomUUID();
        List<DocumentSearchHit> hits = new ArrayList<>();
        // 首块正文超过 8000 code point 预算，迫使装配写完首块后即停止并进入预算停止分类。
        char[] hugeBody = new char[9000];
        java.util.Arrays.fill(hugeBody, '长');
        hits.add(convert(denseOnly(UUID.randomUUID(), documentId, new String(hugeBody), "hash-huge", 0.8)));
        for (int i = 0; i < 3; i++) {
            hits.add(convert(denseOnly(UUID.randomUUID(), documentId, "证据内容 " + i, "hash-" + i, 0.8)));
        }
        UUID lowScore = UUID.randomUUID();
        hits.add(convert(denseOnly(lowScore, documentId, "低分尾部候选", "hash-low", 0.10)));
        var built = BUILDER.buildWithDiagnostics(hits, 12);
        assertThat(built.context().sources()).hasSize(1);
        assertThat(built.dropReasons().get(lowScore))
                .isEqualTo(ContextDropReason.BELOW_SIMILARITY_THRESHOLD);
    }

    @Test
    void expandedContentIsInjectedIntoPromptWithoutForgingIdentity() {
        // expandedContent（句子窗口扩展正文）作为有效正文注入 Prompt，
        // 但证据身份（chunkId/documentId/contentHash）不被覆盖。
        UUID chunkId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Map<String, Object> metadata = Map.of("expandedContent", "扩展后的句子窗口上下文，包含前后句。");
        RetrievalCandidate candidate = new RetrievalCandidate(chunkId, documentId, "文档.md", "标题",
                "原始块正文。", "hash-exp", metadata, 1, 0.8, null, null, 1.0 / 61, null, null);
        var built = BUILDER.buildWithDiagnostics(new ArrayList<>(List.of(convert(candidate))), 5);
        assertThat(built.context().promptSources()).contains("扩展后的句子窗口上下文");
        assertThat(built.context().sources().getFirst().chunkId()).isEqualTo(chunkId);
        assertThat(built.context().sources().getFirst().documentId()).isEqualTo(documentId);
        assertThat(built.context().sources().getFirst().contentHash()).isEqualTo("hash-exp");
        // 装配层有效正文语义：expandedContent 存在时作为正文（既有生产行为）。
        assertThat(built.context().sources().getFirst().content()).contains("扩展后的句子窗口上下文");
    }

    // ==================== 排序语义 ====================

    @Test
    void rrfTieBreakNeverComparesDenseSimilarityWithLexicalScore() {
        // RRF 平分（1/61）时：dense-only（余弦 0.99）与 lexical-only（词项 0.10）两类分数不可比，
        // tie-break 只允许排名空间证据（双路优先 → 最优排名 → chunkId），不得比较分数值。
        UUID idA = new UUID(0, 1);
        UUID idB = new UUID(0, 2);
        UUID documentId = UUID.randomUUID();
        List<RetrievalCandidate> dense = List.of(candidate(idA, documentId, "A", "ha", 1, 0.99, null, null, 0.0));
        List<RetrievalCandidate> lexical = List.of(candidate(idB, documentId, "B", "hb", null, null, 1, 0.10, 0.0));
        List<RetrievalCandidate> fused = FUSION.fuse(dense, lexical, 20);
        assertThat(fused).hasSize(2);
        assertThat(fused.stream().map(RetrievalCandidate::id).toList())
                .as("同 rrfScore、同路数、同最优排名时必须按 chunkId 稳定排序，与分数值无关")
                .containsExactly(idA, idB);
    }

    @Test
    void bothRouteCandidatesWinRrfTiesOverSingleRoute() {
        // RRF 平分时双路召回者优先于单路（排名空间证据）。
        UUID bothRoutes = new UUID(0, 9);
        UUID denseSingle = new UUID(0, 1);
        UUID documentId = UUID.randomUUID();
        List<RetrievalCandidate> dense = List.of(
                candidate(bothRoutes, documentId, "双路", "h-both", 1, 0.7, null, null, 0.0),
                candidate(denseSingle, documentId, "单路", "h-single", 2, 0.9, null, null, 0.0));
        List<RetrievalCandidate> lexical = List.of(
                candidate(bothRoutes, documentId, "双路", "h-both", null, null, 3, 0.5, 0.0));
        List<RetrievalCandidate> fused = FUSION.fuse(dense, lexical, 20);
        assertThat(fused.getFirst().id()).isEqualTo(bothRoutes);
        assertThat(fused.getFirst().rrfScore()).isGreaterThan(fused.get(1).rrfScore());
    }

    @Test
    void selectorPreservesTypedRankingOrderAndLegacySimilarityOrder() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        // 管线类型化输出：admission 分数与排名顺序相反，选择器必须保留排名顺序。
        List<DocumentSearchHit> pipelineHits = convertAll(
                denseOnly(new UUID(0, 1), docA, "A 排名第一", "ha1", 0.5),
                denseOnly(new UUID(0, 2), docB, "B 排名第二", "hb1", 0.95));
        List<DocumentSearchHit> preserved = SELECTOR.select(pipelineHits, 12);
        assertThat(preserved).extracting(DocumentSearchHit::id)
                .as("携带类型化评分的管线输出不得被准入分数重排（RRF/Rerank 排名必须保留）")
                .containsExactly(new UUID(0, 1), new UUID(0, 2));

        // 旧版 Dense 直连输入（无类型化评分元数据）：保持相似度降序兼容行为。
        List<DocumentSearchHit> legacyHits = List.of(
                new DocumentSearchHit(new UUID(0, 1), docA, "文档.md", "标题", "A 弱", "ha", 0.5, Map.of()),
                new DocumentSearchHit(new UUID(0, 2), docB, "文档.md", "标题", "B 强", "hb", 0.95, Map.of()));
        List<DocumentSearchHit> sorted = SELECTOR.select(legacyHits, 12);
        assertThat(sorted).extracting(DocumentSearchHit::id)
                .as("旧版输入保持既有相似度排序行为")
                .containsExactly(new UUID(0, 2), new UUID(0, 1));
    }

    @Test
    void typedScoreMetadataIsPresentAndComplete() {
        RetrievalCandidate candidate = candidate(
                UUID.randomUUID(), UUID.randomUUID(), "正文", "hash", 2, 0.55, 1, 0.6, 0.028);
        List<RetrievalCandidate> reranked = RERANKER.rerank("查询", List.of(candidate), 10);
        DocumentSearchHit hit = convert(reranked.getFirst());
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) hit.metadata().get(RetrievalScores.METADATA_KEY);
        assertThat(scores).isNotNull();
        assertThat(scores.get(RetrievalScores.DENSE_SIMILARITY)).isEqualTo(0.55);
        assertThat(scores.get(RetrievalScores.LEXICAL_SCORE)).isEqualTo(0.6);
        assertThat(scores.get(RetrievalScores.RRF_SCORE)).isEqualTo(0.028);
        assertThat(scores.get(RetrievalScores.RERANK_SCORE)).isNotNull();
        assertThat(scores.get(RetrievalScores.RERANKER)).isEqualTo(SemanticHeuristicReranker.NAME);
        assertThat(scores.get(RetrievalScores.ADMISSION_BASIS)).isEqualTo(RetrievalScores.BASIS_DENSE_AND_LEXICAL);
        assertThat((double) scores.get(RetrievalScores.ADMISSION_SCORE)).isEqualTo(hit.similarity());
    }
}
