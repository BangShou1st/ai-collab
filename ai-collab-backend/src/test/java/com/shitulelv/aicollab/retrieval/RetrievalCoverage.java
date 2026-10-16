package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * D-RAG-01A B 部分：检索覆盖指标（纯函数、确定性）。
 *
 * <p>把一次检索拆成两级，用同一份期望证据集合分别观察，从而把损失归因到正确的那一级：</p>
 * <ol>
 *   <li><b>向量候选</b>（{@code DocumentSearchService.search} 的返回，Top 8 / Top 20）——
 *       候选里就找不到正确证据，属于 <b>“数据库没召回”</b>（{@code dbMissCount}）；</li>
 *   <li><b>上下文保留来源</b>（{@code KnowledgeContextBuilder.build} 的返回）——
 *       候选里有、保留下来的没有，属于 <b>“上下文丢弃”</b>（{@code contextDropCount}）。</li>
 * </ol>
 *
 * <p>三个主指标：{@code retrievalRecall}（候选命中率）、
 * {@code contextEvidenceRetention}（正确证据在上下文中的留存率）、
 * {@code documentCoverage}（含正确证据的文档被覆盖的比例，候选级与上下文级各一份）。</p>
 *
 * <p>三者的恒等关系：{@code dbMissCount + contextDropCount + retainedExpected == expected.size()}。
 * 这里<b>不</b>度量模型回答质量：没有任何模型调用，向量与候选都是确定性的。</p>
 */
public final class RetrievalCoverage {
    /** 有期望证据、比率有定义。 */
    public static final String MEASURED = "MEASURED";
    /**
     * 期望证据集合为空：Recall/Retention 是 0/0，<b>未定义</b>。
     * 归因是“本来就没有可召回的期望证据”，既不是满分，也不能当作检索失败计入。
     */
    public static final String UNDEFINED_NO_EXPECTED_EVIDENCE = "UNDEFINED_NO_EXPECTED_EVIDENCE";

    private RetrievalCoverage() {
    }

    /** 一条“正确证据”：期望被召回的分块，及其所属文档与可读标签。 */
    public record ExpectedEvidence(UUID documentId, UUID chunkId, String label) {
    }

    public static Map<String, Object> measure(
            int topK,
            int contextLimit,
            List<ExpectedEvidence> expected,
            List<DocumentSearchHit> candidates,
            KnowledgeContext context) {

        Set<UUID> expectedChunks = new LinkedHashSet<>();
        Set<UUID> expectedDocuments = new LinkedHashSet<>();
        for (ExpectedEvidence evidence : expected) {
            expectedChunks.add(evidence.chunkId());
            expectedDocuments.add(evidence.documentId());
        }

        Map<UUID, Integer> candidateRanks = new LinkedHashMap<>();
        Map<UUID, Double> candidateSimilarity = new LinkedHashMap<>();
        Set<UUID> candidateChunks = new LinkedHashSet<>();
        for (int index = 0; index < candidates.size(); index++) {
            DocumentSearchHit hit = candidates.get(index);
            candidateChunks.add(hit.id());
            candidateRanks.putIfAbsent(hit.id(), index + 1);
            candidateSimilarity.putIfAbsent(hit.id(), hit.similarity());
        }

        Set<UUID> retainedChunks = new LinkedHashSet<>();
        for (KnowledgeSource source : context.sources()) {
            retainedChunks.add(source.chunkId());
        }

        int recalledExpected = intersectionSize(expectedChunks, candidateChunks);
        int retainedExpected = intersectionSize(expectedChunks, retainedChunks);
        int notRecalledInTopK = expectedChunks.size() - recalledExpected;
        int droppedByContext = recalledExpected - retainedExpected;
        // 文档覆盖率按“该文档的期望证据是否存活”计，而不是“该文档是否出现过任何来源”：
        // 同一文档里被召回的噪声分块不能替它算作已覆盖。
        int expectedDocumentHits = coveredExpectedDocuments(expected, candidateChunks);
        int retainedDocumentHits = coveredExpectedDocuments(expected, retainedChunks);
        int unexpectedSourcesRetained = 0;
        int belowThresholdCandidates = 0;
        for (UUID chunk : candidateChunks) {
            if (!retainedChunks.contains(chunk)) belowThresholdCandidates++;
        }
        for (UUID chunk : retainedChunks) {
            if (!expectedChunks.contains(chunk)) unexpectedSourcesRetained++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topK", topK);
        result.put("contextLimit", contextLimit);
        result.put("expectedEvidenceCount", expectedChunks.size());
        result.put("candidateCount", candidates.size());
        result.put("retainedSourceCount", context.sources().size());
        // 期望证据为空时 Recall/Retention 是 0/0：比率未定义（下面写 null），
        // 这既不是满分也不是检索失败，必须能和不合格的 0.0 区分开。
        result.put("retrievalStatus", expectedChunks.isEmpty()
                ? UNDEFINED_NO_EXPECTED_EVIDENCE : MEASURED);
        result.put("retrievalRecall", ratio(recalledExpected, expectedChunks.size()));
        result.put("contextEvidenceRetention", ratio(retainedExpected, expectedChunks.size()));
        result.put("documentCoverage", documentCoverage(
                expectedDocumentHits, retainedDocumentHits, expectedDocuments.size()));
        // 两个损失计数刻意分开：notRecalledInTopK 是候选窗口内没有，droppedByContext 是候选有、
        // 上下文没保留。只有把 Top 8 与 Top 20 两次观察放在一起，才能判定前者是窗口截断还是
        // 真正没命中（见 classification）。
        result.put("notRecalledInTopK", notRecalledInTopK);
        result.put("droppedByContext", droppedByContext);
        result.put("unexpectedSourcesRetained", unexpectedSourcesRetained);
        result.put("candidatesRejectedByThresholdOrDedup", belowThresholdCandidates);
        result.put("expectedEvidence", describeExpected(
                expected, candidateRanks, candidateSimilarity, retainedChunks));
        result.put("candidateSources", describeCandidates(
                candidates, expectedChunks));
        result.put("retainedSources", describeRetained(context.sources(), expectedChunks));
        return result;
    }

    private static Map<String, Object> documentCoverage(
            int candidateHits, int retainedHits, int expectedDocuments) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("expectedDocumentCount", expectedDocuments);
        coverage.put("candidateDocumentsWithEvidence", candidateHits);
        coverage.put("retainedDocumentsWithEvidence", retainedHits);
        coverage.put("candidateRatio", ratio(candidateHits, expectedDocuments));
        coverage.put("retainedRatio", ratio(retainedHits, expectedDocuments));
        return coverage;
    }

    /**
     * 文档覆盖率：某文档只要有<b>任意一条被列为期望证据的分块</b>存活，就算该文档被覆盖。
     * 同一文档中的非期望分块（噪声）不能替它计数。
     */
    private static int coveredExpectedDocuments(
            List<ExpectedEvidence> expected, Set<UUID> survivingChunks) {
        Set<UUID> covered = new LinkedHashSet<>();
        for (ExpectedEvidence evidence : expected) {
            if (survivingChunks.contains(evidence.chunkId())) {
                covered.add(evidence.documentId());
            }
        }
        return covered.size();
    }

    private static List<Map<String, Object>> describeExpected(
            List<ExpectedEvidence> expected,
            Map<UUID, Integer> ranks,
            Map<UUID, Double> similarities,
            Set<UUID> retainedChunks) {
        List<Map<String, Object>> described = new ArrayList<>(expected.size());
        for (ExpectedEvidence evidence : expected) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", evidence.label());
            item.put("documentId", evidence.documentId().toString());
            item.put("chunkId", evidence.chunkId().toString());
            Integer rank = ranks.get(evidence.chunkId());
            item.put("recalledInCandidates", rank != null);
            // 候选里排第几，是“数据库召回得好不好”的直接观察值
            item.put("candidateRank", rank);
            item.put("candidateSimilarity", similarities.get(evidence.chunkId()));
            item.put("retainedInContext", retainedChunks.contains(evidence.chunkId()));
            described.add(item);
        }
        return described;
    }

    private static List<Map<String, Object>> describeCandidates(
            List<DocumentSearchHit> candidates, Set<UUID> expectedChunks) {
        List<Map<String, Object>> described = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            DocumentSearchHit hit = candidates.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", index + 1);
            item.put("chunkId", hit.id().toString());
            item.put("documentId", hit.documentId().toString());
            item.put("filename", hit.originalFilename());
            item.put("similarity", round(hit.similarity()));
            item.put("expectedEvidence", expectedChunks.contains(hit.id()));
            described.add(item);
        }
        return described;
    }

    private static List<Map<String, Object>> describeRetained(
            List<KnowledgeSource> sources, Set<UUID> expectedChunks) {
        List<Map<String, Object>> described = new ArrayList<>(sources.size());
        for (KnowledgeSource source : sources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", source.rank());
            item.put("chunkId", source.chunkId().toString());
            item.put("documentId", source.documentId().toString());
            item.put("filename", source.originalFilename());
            item.put("heading", source.heading());
            item.put("similarity", round(source.similarity()));
            item.put("expectedEvidence", expectedChunks.contains(source.chunkId()));
            described.add(item);
        }
        return described;
    }

    /**
     * 归因：把同一用例的 Top 8 与 Top 20 两次观察放在一起，区分三种情况。
     *
     * <ul>
     *   <li>{@code WINDOW_TRUNCATED}——Top 8 没进、Top 20 进了：正确证据在库里，
     *       是候选窗口太小，不是“数据库没召回”；</li>
     *   <li>{@code DB_MISS}——Top 20 也没有：在当前相似度/指纹/项目权限范围内数据库没召回；</li>
     *   <li>{@code RECALLED}——Top 8 就进了。</li>
     * </ul>
     *
     * <p>再用 {@code droppedByContext} 单独指出“上下文丢弃”：候选召回成功但被条数上限或
     * 正文预算挡在上下文之外。两者互不掩盖。</p>
     */
    public static Map<String, Object> classify(
            String caseName, Map<String, Object> top8, Map<String, Object> top20) {
        Map<String, Object> classification = new LinkedHashMap<>();
        classification.put("case", caseName);
        classification.put("status", top8.get("retrievalStatus"));
        classification.put("recallAt8", top8.get("retrievalRecall"));
        classification.put("recallAt20", top20.get("retrievalRecall"));
        classification.put("contextEvidenceRetention", top8.get("contextEvidenceRetention"));
        classification.put("dbMissCount", top20.get("notRecalledInTopK"));
        classification.put("droppedByContext", top8.get("droppedByContext"));
        classification.put("verdict", verdictFor(top8, top20));
        return classification;
    }

    private static String verdictFor(Map<String, Object> top8, Map<String, Object> top20) {
        int expected = (int) top8.get("expectedEvidenceCount");
        // 没有期望证据就没有“召回”可言：不判失败，明确标成未定义。
        if (expected == 0) return UNDEFINED_NO_EXPECTED_EVIDENCE;
        int missedAt8 = (int) top8.get("notRecalledInTopK");
        int missedAt20 = (int) top20.get("notRecalledInTopK");
        if (missedAt8 == 0) return "RECALLED_AT_TOP8";
        if (missedAt20 < missedAt8) return "WINDOW_TRUNCATED";
        return "DB_MISS";
    }

    /**
     * 跨用例汇总：把每个用例的计数相加后再取比值，避免简单的比率平均掩盖样本量差异。
     *
     * <p>期望证据为空的用例（{@link #UNDEFINED_NO_EXPECTED_EVIDENCE}）对分子分母都不贡献，
     * 因此不会拉低指标、也不会被当成失败；但会被计入 {@code undefinedCaseCount} 显式暴露，
     * 而不是静默消失。全部用例都未定义时，汇总比率同样返回 {@code null}。</p>
     */
    public static Map<String, Object> aggregate(List<Map<String, Object>> perCaseResults) {
        int expected = 0;
        int recalled = 0;
        int retained = 0;
        int dbMiss = 0;
        int contextDrop = 0;
        int expectedDocuments = 0;
        int candidateDocuments = 0;
        int retainedDocuments = 0;
        int unexpected = 0;
        int undefinedCases = 0;
        for (Map<String, Object> item : perCaseResults) {
            if (UNDEFINED_NO_EXPECTED_EVIDENCE.equals(item.get("retrievalStatus"))) undefinedCases++;
            int itemExpected = (int) item.get("expectedEvidenceCount");
            int itemDbMiss = (int) item.get("notRecalledInTopK");
            int itemDrop = (int) item.get("droppedByContext");
            expected += itemExpected;
            recalled += itemExpected - itemDbMiss;
            retained += itemExpected - itemDbMiss - itemDrop;
            dbMiss += itemDbMiss;
            contextDrop += itemDrop;
            unexpected += (int) item.get("unexpectedSourcesRetained");
            @SuppressWarnings("unchecked")
            Map<String, Object> coverage = (Map<String, Object>) item.get("documentCoverage");
            expectedDocuments += (int) coverage.get("expectedDocumentCount");
            candidateDocuments += (int) coverage.get("candidateDocumentsWithEvidence");
            retainedDocuments += (int) coverage.get("retainedDocumentsWithEvidence");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseCount", perCaseResults.size());
        summary.put("undefinedCaseCount", undefinedCases);
        summary.put("retrievalStatus", expected == 0 ? UNDEFINED_NO_EXPECTED_EVIDENCE : MEASURED);
        summary.put("expectedEvidenceCount", expected);
        summary.put("retrievalRecall", ratio(recalled, expected));
        summary.put("contextEvidenceRetention", ratio(retained, expected));
        summary.put("documentCoverage", documentCoverage(
                candidateDocuments, retainedDocuments, expectedDocuments));
        summary.put("notRecalledInTopK", dbMiss);
        summary.put("droppedByContext", contextDrop);
        summary.put("unexpectedSourcesRetained", unexpected);
        return summary;
    }

    private static int intersectionSize(Set<UUID> left, Set<UUID> right) {
        int size = 0;
        for (UUID value : left) {
            if (right.contains(value)) size++;
        }
        return size;
    }

    /** 分母为 0 时返回 null：此时“比率”无意义，不伪装成 0 或 1。 */
    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : round((double) numerator / denominator);
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
