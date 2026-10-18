package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.retrieval.RetrievalCoverage.ExpectedEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标口径自身的确定性单测：用合成候选验证
 * Retrieval Recall / Context Evidence Retention / Document Coverage 与“数据库没召回 vs 上下文丢弃”的归因。
 *
 * <p>本类不连数据库、不调用模型：它证明的是<b>度量口径</b>正确，
 * 真实向量召回表现由 {@code RetrievalCoveragePostgresTest}（Testcontainers）给出。</p>
 */
class RetrievalCoverageTest {
    private final KnowledgeContextBuilder builder = new KnowledgeContextBuilder();

    private static DocumentSearchHit hit(UUID documentId, String content, String hash, double similarity) {
        return new DocumentSearchHit(
                UUID.randomUUID(), documentId, "文档.pdf", "标题", content, hash, similarity, Map.of());
    }

    @Test
    void separatesDatabaseMissFromContextDrop() {
        // 期望两条证据：一在候选第 1 位（会被保留），二在候选第 9 位（topK=8 时数据库没给）。
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        List<DocumentSearchHit> fullTop20 = new ArrayList<>();
        DocumentSearchHit expectedFirst = hit(documentA, "证据甲", "hash-a", 0.95);
        fullTop20.add(expectedFirst);
        for (int i = 1; i <= 7; i++) fullTop20.add(hit(documentA, "噪声" + i, "noise-" + i, 0.5));
        DocumentSearchHit expectedNinth = hit(documentB, "证据乙", "hash-b", 0.45);
        fullTop20.add(expectedNinth);

        var candidatesTop8 = fullTop20.subList(0, 8);
        var context = builder.build(candidatesTop8);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(documentA, expectedFirst.id(), "证据甲"),
                new ExpectedEvidence(documentB, expectedNinth.id(), "证据乙"));

        var measured = RetrievalCoverage.measure(8, 8, expected, candidatesTop8, context);

        assertThat(measured.get("expectedEvidenceCount")).isEqualTo(2);
        assertThat(measured.get("retrievalRecall")).isEqualTo(0.5d);
        assertThat(measured.get("contextEvidenceRetention")).isEqualTo(0.5d);
        assertThat(measured.get("notRecalledInTopK")).isEqualTo(1);
        assertThat(measured.get("droppedByContext")).isEqualTo(0);
        // 8 个候选里默认只保留 5 个：期望证据占 1 个，剩下 4 个是噪声来源。
        assertThat(measured.get("unexpectedSourcesRetained")).isEqualTo(4);
    }

    @Test
    void attributesWindowTruncationWhenTop20WouldHaveRecalled() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        List<DocumentSearchHit> fullTop20 = new ArrayList<>();
        DocumentSearchHit expectedFirst = hit(documentA, "证据甲", "hash-a", 0.95);
        fullTop20.add(expectedFirst);
        for (int i = 1; i <= 7; i++) fullTop20.add(hit(documentA, "噪声" + i, "noise-" + i, 0.5));
        DocumentSearchHit expectedNinth = hit(documentB, "证据乙", "hash-b", 0.45);
        fullTop20.add(expectedNinth);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(documentA, expectedFirst.id(), "证据甲"),
                new ExpectedEvidence(documentB, expectedNinth.id(), "证据乙"));

        var top8 = RetrievalCoverage.measure(8, 8, expected,
                fullTop20.subList(0, 8), builder.build(fullTop20.subList(0, 8)));
        var top20 = RetrievalCoverage.measure(20, 8, expected,
                fullTop20, builder.build(fullTop20));

        assertThat(top8.get("retrievalRecall")).isEqualTo(0.5d);
        assertThat(top20.get("retrievalRecall")).isEqualTo(1.0d);
        var classification = RetrievalCoverage.classify("窗口截断", top8, top20);
        assertThat(classification.get("verdict")).isEqualTo("WINDOW_TRUNCATED");
        assertThat(classification.get("recallAt8")).isEqualTo(0.5d);
        assertThat(classification.get("recallAt20")).isEqualTo(1.0d);
    }

    @Test
    void attributesDatabaseMissWhenEvenTop20LacksTheEvidence() {
        UUID document = UUID.randomUUID();
        DocumentSearchHit expectedEvidence = hit(document, "库里根本没有", "hash-x", 0.9);
        List<DocumentSearchHit> candidates = List.of(hit(document, "无关内容", "hash-y", 0.5));
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(document, expectedEvidence.id(), "缺失证据"));

        var top8 = RetrievalCoverage.measure(8, 8, expected, candidates, builder.build(candidates));
        var top20 = RetrievalCoverage.measure(20, 8, expected, candidates, builder.build(candidates));

        assertThat(top8.get("retrievalRecall")).isEqualTo(0.0d);
        assertThat(RetrievalCoverage.classify("真没召回", top8, top20).get("verdict"))
                .isEqualTo("DB_MISS");
    }

    @Test
    void attributesContextDropWhenBudgetExhaustionRemovesRecalledEvidence() {
        UUID document = UUID.randomUUID();
        // 候选里第一条是巨块把 8000 字符预算吃光，最后一条正确证据虽然被召回却进不了上下文。
        List<DocumentSearchHit> candidates = new ArrayList<>();
        candidates.add(hit(document, "巨".repeat(9000), "huge", 0.99));
        DocumentSearchHit expectedEvidence = hit(document, "被预算挤掉的证据", "target", 0.5);
        candidates.add(expectedEvidence);
        var context = builder.buildWithDiagnostics(candidates, 12);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(document, expectedEvidence.id(), "被预算挤掉的证据"));

        var measured = RetrievalCoverage.measure(8, 12, expected, candidates, context);

        assertThat(measured.get("retrievalRecall")).isEqualTo(1.0d);
        assertThat(measured.get("contextEvidenceRetention")).isEqualTo(0.0d);
        assertThat(measured.get("notRecalledInTopK")).isEqualTo(0);
        assertThat(measured.get("droppedByContext")).isEqualTo(1);
    }

    @Test
    void separatesContextDropFromSourceCountCap() {
        UUID document = UUID.randomUUID();
        List<DocumentSearchHit> candidates = new ArrayList<>();
        for (int i = 1; i <= 8; i++) candidates.add(hit(document, "证据" + i, "hash-" + i, 1.0 - i * 0.01));
        DocumentSearchHit eighth = candidates.get(7);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(document, eighth.id(), "第八条"));

        // 知识问答默认上限 5：第 8 条被召回却被条数上限挡在上下文之外。
        var qaContext = builder.buildWithDiagnostics(candidates, KnowledgeContextBuilder.DEFAULT_MAX_SOURCES);
        var qaMeasured = RetrievalCoverage.measure(8, KnowledgeContextBuilder.DEFAULT_MAX_SOURCES,
                expected, candidates, qaContext);
        assertThat(qaMeasured.get("retrievalRecall")).isEqualTo(1.0d);
        assertThat(qaMeasured.get("contextEvidenceRetention")).isEqualTo(0.0d);
        assertThat(qaMeasured.get("droppedByContext")).isEqualTo(1);

        // Agent 允许 12 个来源：同一条证据保留下来，丢弃计数归零。
        var agentContext = builder.buildWithDiagnostics(candidates, 12);
        var agentMeasured = RetrievalCoverage.measure(8, 12, expected, candidates, agentContext);
        assertThat(agentMeasured.get("contextEvidenceRetention")).isEqualTo(1.0d);
        assertThat(agentMeasured.get("droppedByContext")).isEqualTo(0);
    }

    @Test
    void documentCoverageIsReportedAtBothCandidateAndContextLevel() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        DocumentSearchHit first = hit(documentA, "证据甲", "hash-a", 0.95);
        DocumentSearchHit second = hit(documentB, "证据乙", "hash-b", 0.55);
        List<DocumentSearchHit> candidates = List.of(
                first, hit(documentA, "噪声1", "n1", 0.6),
                hit(documentB, "噪声2", "n2", 0.58), hit(documentB, "噪声3", "n3", 0.57),
                hit(documentB, "噪声4", "n4", 0.56), second);
        // 默认 5 个来源先把 documentA 的两条留下，documentB 的噪声会占满名额。
        var context = builder.build(candidates);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(documentA, first.id(), "证据甲"),
                new ExpectedEvidence(documentB, second.id(), "证据乙"));

        var measured = RetrievalCoverage.measure(8, 5, expected, candidates, context);

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) measured.get("documentCoverage");
        assertThat(coverage.get("expectedDocumentCount")).isEqualTo(2);
        assertThat(coverage.get("candidateRatio")).isEqualTo(1.0d);
        assertThat((double) coverage.get("retainedRatio")).isLessThan(1.0d);
    }

    @Test
    void emptyExpectationIsUndefinedNotAFailure() {
        UUID document = UUID.randomUUID();
        List<DocumentSearchHit> candidates = List.of(hit(document, "无关", "hash", 0.9));
        var measured = RetrievalCoverage.measure(8, 5, List.of(), candidates, builder.build(candidates));

        // 0/0 是未定义：比率写 null，且状态显式标成未定义。
        assertThat(measured.get("retrievalStatus"))
                .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
        assertThat(measured.get("retrievalRecall")).isNull();
        assertThat(measured.get("contextEvidenceRetention")).isNull();
        assertThat(measured.get("expectedEvidenceCount")).isEqualTo(0);

        var classification = RetrievalCoverage.classify("未定义", measured, measured);
        assertThat(classification.get("verdict"))
                .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
        assertThat(classification.get("status"))
                .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
        assertThat(classification.get("recallAt8")).isNull();
    }

    @Test
    void undefinedCaseIsDistinguishableFromAGenuineZeroRecall() {
        UUID document = UUID.randomUUID();
        List<DocumentSearchHit> candidates = List.of(hit(document, "无关", "hash", 0.9));

        // 未定义：没有期望证据。
        var undefined = RetrievalCoverage.measure(8, 5, List.of(), candidates, builder.build(candidates));
        // 真失败：有期望证据，但候选里一条都没有。
        var genuineMiss = RetrievalCoverage.measure(8, 5,
                List.of(new ExpectedEvidence(document, UUID.randomUUID(), "缺失证据")),
                candidates, builder.build(candidates));

        assertThat(undefined.get("retrievalStatus"))
                .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
        assertThat(genuineMiss.get("retrievalStatus")).isEqualTo(RetrievalCoverage.MEASURED);
        assertThat(genuineMiss.get("retrievalRecall")).isEqualTo(0.0d);
        // 两者绝不能被混为一谈。
        assertThat(undefined.get("retrievalRecall")).isNull();
        assertThat(genuineMiss.get("retrievalRecall")).isNotEqualTo(undefined.get("retrievalRecall"));
    }

    @Test
    void undefinedCasesDoNotDragDownTheAggregate() {
        UUID document = UUID.randomUUID();
        List<DocumentSearchHit> candidates = List.of(hit(document, "证据", "hash", 0.9));
        var measuredCase = RetrievalCoverage.measure(8, 5,
                List.of(new ExpectedEvidence(document, candidates.getFirst().id(), "证据")),
                candidates, builder.build(candidates));
        // 同一个未定义用例混进来，不得拉低指标。
        var undefinedCase = RetrievalCoverage.measure(8, 5, List.of(), candidates,
                builder.build(candidates));

        var summary = RetrievalCoverage.aggregate(List.of(measuredCase, undefinedCase));

        assertThat(summary.get("caseCount")).isEqualTo(2);
        assertThat(summary.get("undefinedCaseCount")).isEqualTo(1);
        assertThat(summary.get("retrievalStatus")).isEqualTo(RetrievalCoverage.MEASURED);
        assertThat(summary.get("expectedEvidenceCount")).isEqualTo(1);
        assertThat(summary.get("retrievalRecall")).isEqualTo(1.0d);
    }

    @Test
    void aggregateWeighsByCountsNotByCaseAverages() {
        // 用例 A：10 条期望全部召回；用例 B：2 条期望只召回 1 条。
        // 按用例平均会得到 0.75，按计数加权才是 11/12。
        UUID document = UUID.randomUUID();
        List<DocumentSearchHit> many = new ArrayList<>();
        for (int i = 1; i <= 10; i++) many.add(hit(document, "证据" + i, "hash-" + i, 0.9));
        List<ExpectedEvidence> tenExpected = new ArrayList<>();
        for (DocumentSearchHit candidate : many) {
            tenExpected.add(new ExpectedEvidence(document, candidate.id(), "证据"));
        }
        var fullCase = RetrievalCoverage.measure(20, 12, tenExpected, many, builder.build(many, 12));

        DocumentSearchHit only = hit(document, "唯一的证据", "only", 0.9);
        var partialCase = RetrievalCoverage.measure(20, 12,
                List.of(new ExpectedEvidence(document, only.id(), "证据"), new ExpectedEvidence(
                        document, UUID.randomUUID(), "缺失")),
                List.of(only), builder.build(List.of(only), 12));

        var summary = RetrievalCoverage.aggregate(List.of(fullCase, partialCase));

        assertThat(summary.get("expectedEvidenceCount")).isEqualTo(12);
        assertThat(summary.get("retrievalRecall")).isEqualTo(round(11d / 12d));
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    // ---- D-RAG-01B：把“候选命中但上下文没保留”拆成互不重叠的四类 ----

    @Test
    void crossDocumentDedupLossIsDistinguishedAndRemovedByTheDiverseEntry() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        DocumentSearchHit first = hit(documentA, "两件文档相同的条款", "same-hash", 0.9);
        DocumentSearchHit second = hit(documentB, "两件文档相同的条款", "same-hash", 0.8);
        List<DocumentSearchHit> candidates = List.of(first, second);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(documentA, first.id(), "原始件"),
                new ExpectedEvidence(documentB, second.id(), "复印件"));

        // 旧入口：全局 contentHash 去重把两份文档合并成一条来源——跨文档引用身份丢失。
        var oldMeasured = RetrievalCoverage.measure(8, 12, expected, candidates,
                builder.buildWithDiagnostics(candidates, 12));
        assertThat(oldMeasured.get("droppedByContext")).isEqualTo(1);
        assertThat(oldMeasured.get("crossDocumentDedupLoss")).isEqualTo(1);
        assertThat(oldMeasured.get("intraDocumentDedupLoss")).isEqualTo(0);
        assertThat(oldMeasured.get("budgetDrop")).isEqualTo(0);
        assertThat(RetrievalCoverage.classify("跨文档去重", oldMeasured, oldMeasured).get("retentionVerdict"))
                .isEqualTo("CROSS_DOCUMENT_DEDUP");

        // 新入口：两份文档各自保留，引用身份可追溯。
        var diverseMeasured = RetrievalCoverage.measure(8, 12, expected, candidates,
                builder.buildDiverseWithDiagnostics(candidates, 12));
        assertThat(diverseMeasured.get("droppedByContext")).isEqualTo(0);
        assertThat(diverseMeasured.get("crossDocumentDedupLoss")).isEqualTo(0);
        assertThat(diverseMeasured.get("contextEvidenceRetention")).isEqualTo(1.0d);
    }

    @Test
    void budgetDropIsDistinguishedAndRemovedByTheDiverseEntry() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        DocumentSearchHit huge = hit(documentA, "巨".repeat(9000), "huge", 0.99);
        DocumentSearchHit shortEvidence = hit(documentB, "另一文档的关键短证据", "short", 0.5);
        List<DocumentSearchHit> candidates = List.of(huge, shortEvidence);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(documentB, shortEvidence.id(), "短证据"));

        // 旧入口：巨块吃光正文预算，其他文档的短证据被挤掉——预算丢弃。
        var oldMeasured = RetrievalCoverage.measure(8, 12, expected, candidates,
                builder.buildWithDiagnostics(candidates, 12));
        assertThat(oldMeasured.get("droppedByContext")).isEqualTo(1);
        assertThat(oldMeasured.get("budgetDrop")).isEqualTo(1);
        assertThat(oldMeasured.get("sourceCapDrop")).isEqualTo(0);
        assertThat(RetrievalCoverage.classify("预算丢弃", oldMeasured, oldMeasured).get("retentionVerdict"))
                .isEqualTo("BUDGET_DROP");

        // 新入口：公平分配预算，短证据仍能进入上下文。
        var diverseMeasured = RetrievalCoverage.measure(8, 8, expected, candidates,
                builder.buildDiverseWithDiagnostics(candidates, 8));
        assertThat(diverseMeasured.get("droppedByContext")).isEqualTo(0);
        assertThat(diverseMeasured.get("budgetDrop")).isEqualTo(0);
        assertThat(diverseMeasured.get("contextEvidenceRetention")).isEqualTo(1.0d);
    }

    @Test
    void sourceCapDropIsReportedWhenSourceSlotsAreFull() {
        UUID document = UUID.randomUUID();
        List<DocumentSearchHit> candidates = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            candidates.add(hit(document, "证据" + i, "hash-" + i, 1.0 - i * 0.01));
        }
        DocumentSearchHit eighth = candidates.get(7);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(document, eighth.id(), "第八条"));

        // 知识问答默认上限 5：第 8 条被召回，却因名额被占满进不了上下文。
        var measured = RetrievalCoverage.measure(8, KnowledgeContextBuilder.DEFAULT_MAX_SOURCES,
                expected, candidates, builder.buildWithDiagnostics(candidates,
                        KnowledgeContextBuilder.DEFAULT_MAX_SOURCES));
        assertThat(measured.get("droppedByContext")).isEqualTo(1);
        assertThat(measured.get("sourceCapDrop")).isEqualTo(1);
        assertThat(measured.get("budgetDrop")).isEqualTo(0);
        assertThat(measured.get("crossDocumentDedupLoss")).isEqualTo(0);
        assertThat(RetrievalCoverage.classify("名额占满", measured, measured).get("retentionVerdict"))
                .isEqualTo("CONTEXT_DROP_SOURCE_CAP");
    }

    @Test
    void aggregateReportsLossBreakdownAndAverageSizes() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        DocumentSearchHit huge = hit(documentA, "巨".repeat(9000), "huge", 0.99);
        DocumentSearchHit shortEvidence = hit(documentB, "另一文档的关键短证据", "short", 0.5);
        var measured = RetrievalCoverage.measure(8, 12,
                List.of(new ExpectedEvidence(documentB, shortEvidence.id(), "短证据")),
                List.of(huge, shortEvidence), builder.buildWithDiagnostics(
                        List.of(huge, shortEvidence), 12));

        var summary = RetrievalCoverage.aggregate(List.of(measured));

        assertThat(summary.get("budgetDrop")).isEqualTo(1);
        assertThat(summary.get("averageCandidateCount")).isEqualTo(2.0d);
        assertThat(summary.get("averageRetainedSourceCount")).isEqualTo(1.0d);
        assertThat((double) summary.get("averageContextPromptCodePoints")).isGreaterThan(0d);
    }

    @Test
    void sourceSlotLossIsNotMisreportedAsCrossDocumentDeduplication() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID documentC = UUID.randomUUID();
        DocumentSearchHit a = hit(documentA, "A", "same-hash", 0.99);
        DocumentSearchHit b = hit(documentB, "B", "b-hash", 0.98);
        DocumentSearchHit c = hit(documentC, "C", "same-hash", 0.97);
        List<DocumentSearchHit> candidates = List.of(a, b, c);
        var measured = RetrievalCoverage.measure(8, 2,
                List.of(new ExpectedEvidence(documentC, c.id(), "C")), candidates,
                builder.buildDiverseWithDiagnostics(candidates, 2));

        assertThat(measured.get("droppedByContext")).isEqualTo(1);
        assertThat(measured.get("sourceCapDrop")).isEqualTo(1);
        assertThat(measured.get("crossDocumentDedupLoss")).isEqualTo(0);
        assertThat(dropReasonCount(measured)).isEqualTo(measured.get("droppedByContext"));
    }

    @Test
    void belowThresholdCandidateIsNotAttributedToTheBodyBudget() {
        UUID document = UUID.randomUUID();
        DocumentSearchHit weak = hit(document, "低分证据", "weak", 0.14);
        List<DocumentSearchHit> candidates = List.of(weak);
        var measured = RetrievalCoverage.measure(8, 8,
                List.of(new ExpectedEvidence(document, weak.id(), "低分证据")), candidates,
                builder.buildDiverseWithDiagnostics(candidates, 8));

        assertThat(measured.get("droppedByContext")).isEqualTo(1);
        assertThat(measured.get("belowSimilarityThresholdDrop")).isEqualTo(1);
        assertThat(measured.get("budgetDrop")).isEqualTo(0);
        assertThat(dropReasonCount(measured)).isEqualTo(measured.get("droppedByContext"));
    }

    @Test
    void emptyBodyCandidateIsNotAttributedToTheBodyBudget() {
        UUID document = UUID.randomUUID();
        DocumentSearchHit empty = hit(document, "   ", "empty", 0.9);
        List<DocumentSearchHit> candidates = List.of(empty);
        var measured = RetrievalCoverage.measure(8, 8,
                List.of(new ExpectedEvidence(document, empty.id(), "空正文")), candidates,
                builder.buildDiverseWithDiagnostics(candidates, 8));

        assertThat(measured.get("droppedByContext")).isEqualTo(1);
        assertThat(measured.get("emptyBodyDrop")).isEqualTo(1);
        assertThat(measured.get("budgetDrop")).isEqualTo(0);
        assertThat(dropReasonCount(measured)).isEqualTo(measured.get("droppedByContext"));
    }

    @Test
    void duplicateAttributionUsesTheActualDropReasonAndIsIndependentOfExpectedOrder() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID documentC = UUID.randomUUID();
        DocumentSearchHit a1 = hit(documentA, "A 原文", "same-hash", 0.99);
        DocumentSearchHit a2 = hit(documentA, "A 同文档副本", "same-hash", 0.98);
        DocumentSearchHit b1 = hit(documentB, "B 相同条款", "same-hash", 0.97);
        DocumentSearchHit c1 = hit(documentC, "C 相同条款", "same-hash", 0.96);
        List<DocumentSearchHit> candidates = List.of(a1, a2, b1, c1);
        var context = builder.buildDiverseWithDiagnostics(candidates, 2);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(documentA, a2.id(), "A 同文档副本"),
                new ExpectedEvidence(documentC, c1.id(), "C 名额不足"));
        List<ExpectedEvidence> reversed = List.of(expected.get(1), expected.get(0));

        var forward = RetrievalCoverage.measure(8, 2, expected, candidates, context);
        var backward = RetrievalCoverage.measure(8, 2, reversed, candidates, context);

        assertThat(forward.get("intraDocumentDedupLoss")).isEqualTo(1);
        assertThat(forward.get("sourceCapDrop")).isEqualTo(1);
        assertThat(forward.get("crossDocumentDedupLoss")).isEqualTo(0);
        assertThat(backward.get("intraDocumentDedupLoss")).isEqualTo(1);
        assertThat(backward.get("sourceCapDrop")).isEqualTo(1);
        assertThat(backward.get("crossDocumentDedupLoss")).isEqualTo(0);
        assertThat(forward.get("droppedByContext")).isEqualTo(2);
        assertThat(dropReasonCount(forward)).isEqualTo(forward.get("droppedByContext"));
        assertThat(dropReasonCount(backward)).isEqualTo(backward.get("droppedByContext"));
    }

    @Test
    void missingDropProvenanceIsExplicitlyUnattributedInsteadOfInferredFromHash() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        DocumentSearchHit a = hit(documentA, "同一条款", "same-hash", 0.99);
        DocumentSearchHit b = hit(documentB, "同一条款", "same-hash", 0.98);
        List<DocumentSearchHit> candidates = List.of(a, b);
        var context = builder.buildDiverse(candidates, 1);

        var measured = RetrievalCoverage.measure(8, 1,
                List.of(new ExpectedEvidence(documentB, b.id(), "来源未知")), candidates, context);

        assertThat(measured.get("crossDocumentDedupLoss")).isEqualTo(0);
        assertThat(measured.get("unattributedDrop")).isEqualTo(1);
        assertThat(dropReasonCount(measured)).isEqualTo(measured.get("droppedByContext"));
    }

    private static int dropReasonCount(Map<String, Object> result) {
        return (int) result.get("crossDocumentDedupLoss")
                + (int) result.get("intraDocumentDedupLoss")
                + (int) result.get("sourceCapDrop")
                + (int) result.get("budgetDrop")
                + (int) result.get("belowSimilarityThresholdDrop")
                + (int) result.get("emptyBodyDrop")
                + (int) result.get("unattributedDrop");
    }
}
