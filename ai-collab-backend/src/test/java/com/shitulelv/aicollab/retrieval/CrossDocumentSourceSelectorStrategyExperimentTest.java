package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-RAG-01C 第二轮 阶段 D：<b>SOURCE_CAP 选择策略对照实验</b>（确定性，纯函数，无数据库、无模型）。
 *
 * <p>比较 A（生产既定两阶段覆盖优先）与 B（{@link SourceCapStrategyExperiment} 的相关度保护实验策略）。
 * 两者使用<b>相同候选输入、相同最终 source limit、相同正文预算</b>，只在选择规则上不同。
 * 本类不改动生产默认算法：B 只存在于测试源码；若 B 有明确净收益且无重要场景退化，再考虑最小生产修复。</p>
 */
class CrossDocumentSourceSelectorStrategyExperimentTest {
    private static final int MAX_SOURCES = KnowledgeContextBuilder.MAX_SOURCES_LIMIT;
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Path EVIDENCE_PATH = Path.of("target", "retrieval-baseline", "2026-10-19",
            "sourcecap-strategy-experiment.json");

    private static final Map<String, Map<String, Object>> EXPERIMENTS = new LinkedHashMap<>();

    private record Case(String name, String note, int maxSources, List<DocumentSearchHit> candidates,
                        List<UUID> expectedChunkIds, List<UUID> expectedDocumentIds) {}

    private record StrategyMetric(String strategy, int expectedTotal, int expectedRetained,
                                  int expectedDocuments, int documentsCovered, int unexpectedSources,
                                  int sourceCount, int promptCodePoints, boolean identityConsistent) {}

    private static DocumentSearchHit hit(UUID chunkId, UUID documentId, String content,
                                         String hash, double similarity) {
        return new DocumentSearchHit(chunkId, documentId, "文档.md", "标题", content, hash, similarity, Map.of());
    }

    private static UUID orderedId(int index) {
        return new UUID(0, index);
    }

    @Test
    void relevanceProtectedStrategyIsComparedAgainstCurrentCoverageFirstStrategy() {
        List<Case> cases = List.of(
                // E1：文档数 ≥ 名额数时，旧策略把全部名额给“每文档一个代表”，
                // 目标文档里“次强但更相关”的第二分块被挤掉——这正是真实基线 #2/#15 的形态。
                weakRepresentativesCrowdOutSecondChunk(),
                // E2：单文档——两策略都应退化为相似度顺序。
                singleDocument(),
                // E3：跨文档相同正文——两策略都必须保留两个独立来源身份。
                identicalBodyAcrossDocuments(),
                // E4：同文档互补分块——名额充足时两策略都应保留。
                complementaryChunksInOneDocument(),
                // E5：大量弱相关文档代表干扰——观察覆盖与相关度取舍。
                weakDistractorDocuments(),
                // E6：D-RAG-01B 的核心保证——单一高分文档不得占满窗口、挤掉次文档的有效证据。
                crossDocumentCoverageGuarantee());

        for (Case testCase : cases) {
            StrategyMetric current = measure("A-coverage-first", testCase, false);
            StrategyMetric protectedStrategy = measure("B-relevance-protected", testCase, true);
            Map<String, Object> experiment = new LinkedHashMap<>();
            experiment.put("case", testCase.name());
            experiment.put("note", testCase.note());
            experiment.put("maxSources", testCase.maxSources());
            experiment.put("A_coverageFirst", describe(current));
            experiment.put("B_relevanceProtected", describe(protectedStrategy));
            experiment.put("deltaExpectedRetained", protectedStrategy.expectedRetained() - current.expectedRetained());
            experiment.put("deltaDocumentsCovered", protectedStrategy.documentsCovered() - current.documentsCovered());
            experiment.put("deltaUnexpectedSources", protectedStrategy.unexpectedSources() - current.unexpectedSources());
            EXPERIMENTS.put(testCase.name(), experiment);

            // 硬不变量：两策略都不得超过名额与预算，且来源身份必须自洽。
            for (StrategyMetric metric : List.of(current, protectedStrategy)) {
                assertThat(metric.sourceCount()).as("%s/%s：不得超过 source limit", testCase.name(), metric.strategy())
                        .isLessThanOrEqualTo(testCase.maxSources());
                assertThat(metric.promptCodePoints()).as("%s/%s：正文预算不得超限", testCase.name(), metric.strategy())
                        .isLessThanOrEqualTo(SourceCapStrategyExperiment.MAX_PROMPT_SOURCE_CODE_POINTS);
                assertThat(metric.identityConsistent()).as("%s/%s：来源身份必须自洽", testCase.name(), metric.strategy())
                        .isTrue();
            }
        }

        // E1 必须真的产生可测量的差异，否则实验形同虚设。
        var crowded = EXPERIMENTS.get("E1-弱代表挤占第二分块");
        assertThat((int) crowded.get("deltaExpectedRetained"))
                .as("E1：相关度保护策略应能保留被弱代表挤掉的更相关证据")
                .isGreaterThan(0);

        // E6 反向：相关度保护策略在 D-RAG-01B 的“覆盖保证”场景上会退化——取舍必须如实记录。
        var guarantee = EXPERIMENTS.get("E6-次文档证据不可被高分文档挤掉");
        assertThat((int) guarantee.get("deltaExpectedRetained"))
                .as("E6：相关度保护策略会牺牲次文档覆盖保证（取舍，不直接改生产）")
                .isLessThan(0);
        assertThat(currentRetained(guarantee))
                .as("E6：生产既定策略必须保住次文档证据（D-RAG-01B 保证成立）").isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static int currentRetained(Map<String, Object> experiment) {
        return (int) ((Map<String, Object>) experiment.get("A_coverageFirst")).get("expectedRetained");
    }

    private static Case weakRepresentativesCrowdOutSecondChunk() {
        List<DocumentSearchHit> candidates = new ArrayList<>();
        List<UUID> expected = new ArrayList<>();
        List<UUID> expectedDocs = new ArrayList<>();
        // 目标文档：chunk#0 是较强但非最相关（0.60），chunk#1 是真正期望证据（0.59）。
        UUID targetDoc = orderedId(1000);
        UUID targetRep = orderedId(1);
        UUID targetEvidence = orderedId(2);
        candidates.add(hit(targetRep, targetDoc, "目标文档代表分块", "h-rep", 0.60));
        candidates.add(hit(targetEvidence, targetDoc, "目标文档真正相关证据", "h-key", 0.59));
        expected.add(targetEvidence);
        expectedDocs.add(targetDoc);
        // 另外 12 个文档，每个只有一个较弱代表：文档数 ≥ 名额数（12），旧策略会把名额全部给代表。
        for (int i = 0; i < 12; i++) {
            candidates.add(hit(orderedId(100 + i), orderedId(2000 + i),
                    "弱文档 " + i + " 代表分块", "hw-" + i, 0.45 - i * 0.01));
        }
        return new Case("E1-弱代表挤占第二分块",
                "文档数(13) ≥ 名额数(12)：旧策略把全部名额给代表，目标文档第二分块被挤掉", MAX_SOURCES, candidates,
                expected, expectedDocs);
    }

    private static Case singleDocument() {
        List<DocumentSearchHit> candidates = new ArrayList<>();
        UUID doc = orderedId(1);
        for (int i = 0; i < 4; i++) {
            candidates.add(hit(orderedId(i + 1), doc, "同文档证据 " + i, "h" + i, 0.9 - i * 0.1));
        }
        List<UUID> expected = List.of(orderedId(2));
        return new Case("E2-单文档", "单文档时跨文档多样性退化为相似度顺序", MAX_SOURCES, candidates, expected, List.of(doc));
    }

    private static Case identicalBodyAcrossDocuments() {
        UUID docA = orderedId(1);
        UUID docB = orderedId(2);
        String body = "跨文档完全相同的条款正文";
        List<DocumentSearchHit> candidates = List.of(
                hit(orderedId(1), docA, body, "same-hash", 0.9),
                hit(orderedId(2), docB, body, "same-hash", 0.8));
        return new Case("E3-跨文档相同正文", "相同 contentHash 但文档不同：必须保留两个独立来源身份",
                MAX_SOURCES, candidates, List.of(orderedId(1), orderedId(2)), List.of(docA, docB));
    }

    private static Case complementaryChunksInOneDocument() {
        UUID doc = orderedId(1);
        List<DocumentSearchHit> candidates = List.of(
                hit(orderedId(1), doc, "互补证据甲", "ha", 0.9),
                hit(orderedId(2), doc, "互补证据乙", "hb", 0.7));
        return new Case("E4-同文档互补分块", "名额充足时同文档互补分块都应保留",
                MAX_SOURCES, candidates, List.of(orderedId(1), orderedId(2)), List.of(doc));
    }

    private static Case weakDistractorDocuments() {
        List<DocumentSearchHit> candidates = new ArrayList<>();
        UUID strong = orderedId(1);
        UUID strongEvidence = orderedId(1);
        candidates.add(hit(strongEvidence, strong, "强相关证据", "hs", 0.95));
        for (int i = 0; i < 15; i++) {
            candidates.add(hit(orderedId(500 + i), orderedId(600 + i), "弱干扰 " + i, "hd-" + i, 0.20));
        }
        return new Case("E5-弱相关干扰文档", "大量弱相关文档代表可能占用名额",
                MAX_SOURCES, candidates, List.of(strongEvidence), List.of(strong));
    }

    private static Case crossDocumentCoverageGuarantee() {
        UUID documentA = orderedId(1);
        UUID documentB = orderedId(2);
        List<DocumentSearchHit> candidates = new ArrayList<>();
        // 文档 A 有 6 条高分候选，文档 B 只有 1 条中等相关证据——单一高分文档会占满窗口。
        for (int i = 0; i < 6; i++) {
            candidates.add(hit(orderedId(i + 1), documentA, "A 的证据 " + i, "a-hash-" + i, 0.95 - i * 0.01));
        }
        UUID evidenceB = orderedId(100);
        candidates.add(hit(evidenceB, documentB, "B 的关键证据", "b-hash", 0.5));
        return new Case("E6-次文档证据不可被高分文档挤掉",
                "D-RAG-01B 核心保证：单一高分文档不得占满窗口、挤掉次文档的有效证据",
                3, candidates, List.of(evidenceB), List.of(documentB));
    }

    private StrategyMetric measure(String strategy, Case testCase, boolean useProtected) {
        int limit = testCase.maxSources();
        List<DocumentSearchHit> ordered = useProtected
                ? SourceCapStrategyExperiment.selectRelevanceProtected(testCase.candidates(), limit)
                : SourceCapStrategyExperiment.selectCurrent(testCase.candidates(), limit);
        var assembled = SourceCapStrategyExperiment.assemble(ordered, limit);
        Set<UUID> retained = new LinkedHashSet<>(assembled.chunkIds());

        int expectedRetained = 0;
        for (UUID chunk : testCase.expectedChunkIds()) if (retained.contains(chunk)) expectedRetained++;
        int documentsCovered = 0;
        for (UUID document : testCase.expectedDocumentIds()) if (assembled.documentIds().contains(document)) documentsCovered++;

        int unexpected = 0;
        for (UUID chunk : retained) if (!testCase.expectedChunkIds().contains(chunk)) unexpected++;

        // 身份自洽：保留的每个分块都能在候选里找到，并归属于恰好一个文档。
        Map<UUID, UUID> chunkToDocument = new LinkedHashMap<>();
        for (DocumentSearchHit hit : testCase.candidates()) chunkToDocument.put(hit.id(), hit.documentId());
        boolean consistent = true;
        for (UUID chunk : retained) if (!chunkToDocument.containsKey(chunk)) consistent = false;

        return new StrategyMetric(strategy, testCase.expectedChunkIds().size(), expectedRetained,
                testCase.expectedDocumentIds().size(), documentsCovered, unexpected,
                assembled.sourceCount(), assembled.promptCodePoints(), consistent);
    }

    private static Map<String, Object> describe(StrategyMetric metric) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("expectedTotal", metric.expectedTotal());
        item.put("expectedRetained", metric.expectedRetained());
        item.put("expectedDocuments", metric.expectedDocuments());
        item.put("documentsCovered", metric.documentsCovered());
        item.put("unexpectedSourcesRetained", metric.unexpectedSources());
        item.put("sourceCount", metric.sourceCount());
        item.put("promptCodePoints", metric.promptCodePoints());
        item.put("identityConsistent", metric.identityConsistent());
        return item;
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("note", "D-RAG-01C 第二轮阶段 D：SOURCE_CAP 选择策略对照（确定性，纯函数）。"
                + "A=生产既定两阶段覆盖优先；B=测试侧相关度保护实验策略。"
                + "两者候选输入、source limit、正文预算完全相同；本实验不改变生产默认算法。");
        document.put("maxSources", MAX_SOURCES);
        document.put("experiments", EXPERIMENTS);
        Files.createDirectories(EVIDENCE_PATH.getParent());
        Files.writeString(EVIDENCE_PATH,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document), StandardCharsets.UTF_8);
        System.out.println("SOURCECAP_STRATEGY_EVIDENCE=" + EVIDENCE_PATH.toAbsolutePath());
    }
}
