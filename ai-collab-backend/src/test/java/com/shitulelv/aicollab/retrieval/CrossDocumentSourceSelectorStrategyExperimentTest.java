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
    static final Path EVIDENCE_PATH = Path.of("target", "retrieval-baseline", "p4-rag-quality-01b",
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
                crossDocumentCoverageGuarantee(),
                // E7：P4-RAG-QUALITY-01B 核心反例：候选文档数(3) >= 名额数(3)，
                // 策略 C 压缩代表名额以腾出位置给主文档第二分块，导致必须保留的弱次文档 C1 丢失！
                criticalMinorityDocumentCrowdedOut(),
                // E8：极度紧张名额边界（maxSources = 1）。
                extremeSingleSourceLimit(),
                // E9：相似度并列确定性排序（tie-breaker 稳定性）。
                tieBreakingStability(),
                // E10：长正文超出预算边界（验证 8000 字符预算兜底）。
                longBodyBudgetExceeded(),
                // E11：中等名额多文档竞争（maxSources = 8，10 文档）。
                multiDocumentCompetitionEightSources());

        for (Case testCase : cases) {
            StrategyMetric current = measure("A-coverage-first", testCase, 0);
            StrategyMetric protectedStrategy = measure("B-relevance-protected", testCase, 1);
            StrategyMetric compromiseStrategy = measure("C-conservative-compromise", testCase, 2);
            Map<String, Object> experiment = new LinkedHashMap<>();
            experiment.put("case", testCase.name());
            experiment.put("note", testCase.note());
            experiment.put("maxSources", testCase.maxSources());
            experiment.put("A_coverageFirst", describe(current));
            experiment.put("B_relevanceProtected", describe(protectedStrategy));
            experiment.put("C_conservativeCompromise", describe(compromiseStrategy));
            experiment.put("deltaExpectedRetained_B", protectedStrategy.expectedRetained() - current.expectedRetained());
            experiment.put("deltaDocumentsCovered_B", protectedStrategy.documentsCovered() - current.documentsCovered());
            experiment.put("deltaUnexpectedSources_B", protectedStrategy.unexpectedSources() - current.unexpectedSources());
            experiment.put("deltaExpectedRetained_C", compromiseStrategy.expectedRetained() - current.expectedRetained());
            experiment.put("deltaDocumentsCovered_C", compromiseStrategy.documentsCovered() - current.documentsCovered());
            experiment.put("deltaUnexpectedSources_C", compromiseStrategy.unexpectedSources() - current.unexpectedSources());
            // 保留旧字段以便兼容
            experiment.put("deltaExpectedRetained", protectedStrategy.expectedRetained() - current.expectedRetained());
            experiment.put("deltaDocumentsCovered", protectedStrategy.documentsCovered() - current.documentsCovered());
            experiment.put("deltaUnexpectedSources", protectedStrategy.unexpectedSources() - current.unexpectedSources());
            EXPERIMENTS.put(testCase.name(), experiment);

            // 硬不变量：三策略都不得超过名额与预算，且来源身份必须自洽。
            for (StrategyMetric metric : List.of(current, protectedStrategy, compromiseStrategy)) {
                assertThat(metric.sourceCount()).as("%s/%s：不得超过 source limit", testCase.name(), metric.strategy())
                        .isLessThanOrEqualTo(testCase.maxSources());
                assertThat(metric.promptCodePoints()).as("%s/%s：正文预算不得超限", testCase.name(), metric.strategy())
                        .isLessThanOrEqualTo(SourceCapStrategyExperiment.MAX_PROMPT_SOURCE_CODE_POINTS);
                assertThat(metric.identityConsistent()).as("%s/%s：来源身份必须自洽", testCase.name(), metric.strategy())
                        .isTrue();
            }
        }

        // E1 必须真的产生可测量的差异：B 和 C 都能保留被弱代表挤掉的更相关证据
        var crowded = EXPERIMENTS.get("E1-弱代表挤占第二分块");
        assertThat((int) crowded.get("deltaExpectedRetained_B"))
                .as("E1：相关度保护策略 B 应能保留被弱代表挤掉的更相关证据")
                .isGreaterThan(0);
        assertThat((int) crowded.get("deltaExpectedRetained_C"))
                .as("E1：保守折中策略 C 应能保留被弱代表挤掉的更相关证据")
                .isGreaterThan(0);

        // E6 反向：相关度保护策略 B 牺牲次文档覆盖保证（退化），但保守折中策略 C 完整保住次文档证据（不退化）
        var guarantee = EXPERIMENTS.get("E6-次文档证据不可被高分文档挤掉");
        assertThat((int) guarantee.get("deltaExpectedRetained_B"))
                .as("E6：相关度保护策略 B 会牺牲次文档覆盖保证（取舍，不直接改生产）")
                .isLessThan(0);
        assertThat(currentRetained(guarantee))
                .as("E6：生产既定策略 A 必须保住次文档证据（D-RAG-01B 保证成立）").isEqualTo(1);
        assertThat((int) ((Map<String, Object>) guarantee.get("C_conservativeCompromise")).get("expectedRetained"))
                .as("E6：保守折中策略 C 必须保住次文档证据（与 A 相同，无退化）").isEqualTo(1);
        assertThat((int) guarantee.get("deltaExpectedRetained_C"))
                .as("E6：保守折中策略 C 相比 A 净损失为 0").isEqualTo(0);

        // E7 核心反例：当文档数(3) >= 名额数(3) 时，策略 C 压缩代表名额以释放位置给主文档第二分块，
        // 从而直接挤掉了排在末尾的次文档必须保留的关键证据 C1！
        var e7 = EXPERIMENTS.get("E7-名额紧张时压缩代表名额导致重要次文档丢失");
        assertThat(currentRetained(e7))
                .as("E7：生产策略 A 优先保证跨文档覆盖，成功保留次文档证据 C1").isEqualTo(1);
        assertThat((int) ((Map<String, Object>) e7.get("B_relevanceProtected")).get("expectedRetained"))
                .as("E7：策略 B 因相对阈值丢弃次文档 C1（退化）").isEqualTo(0);
        assertThat((int) ((Map<String, Object>) e7.get("C_conservativeCompromise")).get("expectedRetained"))
                .as("E7：策略 C 压缩代表名额至 2，第三文档代表 C1 在第一阶段未入选，并在第二阶段被主文档 A2 挤出（退化确认！）").isEqualTo(0);
        assertThat((int) e7.get("deltaExpectedRetained_C"))
                .as("E7：策略 C 相比生产策略 A 存在明确证据损失退化 (-1)").isEqualTo(-1);
        assertThat((int) e7.get("deltaDocumentsCovered_C"))
                .as("E7：策略 C 相比生产策略 A 存在明确文档覆盖退化 (-1)").isEqualTo(-1);

        // E8：极度紧张名额边界（maxSources = 1），各策略都仅保留最高分候选
        var e8 = EXPERIMENTS.get("E8-极度紧张名额边界");
        assertThat(((StrategyMetric) measure("A-coverage-first", cases.get(7), 0)).sourceCount()).isEqualTo(1);

        // E10：正文预算边界测试，三策略都必须被 MAX_PROMPT_SOURCE_CODE_POINTS 截断
        var e10 = EXPERIMENTS.get("E10-长正文超出预算边界");
        int promptCpA = (int) ((Map<String, Object>) e10.get("A_coverageFirst")).get("promptCodePoints");
        assertThat(promptCpA).isLessThanOrEqualTo(SourceCapStrategyExperiment.MAX_PROMPT_SOURCE_CODE_POINTS);
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

    private static Case criticalMinorityDocumentCrowdedOut() {
        UUID documentA = orderedId(10);
        UUID documentB = orderedId(20);
        UUID documentC = orderedId(30);

        UUID chunkA1 = orderedId(11);
        UUID chunkA2 = orderedId(12);
        UUID chunkB1 = orderedId(21);
        UUID chunkC1 = orderedId(31);

        List<DocumentSearchHit> candidates = List.of(
                hit(chunkA1, documentA, "主文档 A 代表条款", "a-hash-1", 0.95),
                hit(chunkA2, documentA, "主文档 A 第二高分条款", "a-hash-2", 0.94),
                hit(chunkB1, documentB, "次文档 B 代表条款", "b-hash-1", 0.50),
                hit(chunkC1, documentC, "次文档 C 必须保留的独立关键证据", "c-hash-1", 0.40)
        );
        return new Case("E7-名额紧张时压缩代表名额导致重要次文档丢失",
                "P4-RAG-QUALITY-01B 核心反例：候选文档数(3) >= 名额数(3)，策略 C 压缩代表名额至 2，导致 C1 丢失",
                3, candidates, List.of(chunkC1), List.of(documentC));
    }

    private static Case extremeSingleSourceLimit() {
        UUID docA = orderedId(101);
        UUID docB = orderedId(102);
        UUID chunkA = orderedId(1011);
        UUID chunkB = orderedId(1021);
        List<DocumentSearchHit> candidates = List.of(
                hit(chunkA, docA, "单名额最高分候选", "hash-top-a", 0.95),
                hit(chunkB, docB, "单名额次高分候选", "hash-top-b", 0.85)
        );
        return new Case("E8-极度紧张名额边界",
                "maxSources = 1 时各策略均仅保留全局最高分候选",
                1, candidates, List.of(chunkA), List.of(docA));
    }

    private static Case tieBreakingStability() {
        UUID docA = orderedId(201);
        UUID docB = orderedId(202);
        UUID docC = orderedId(203);
        UUID chunkA = orderedId(2011);
        UUID chunkB = orderedId(2021);
        UUID chunkC = orderedId(2031);
        List<DocumentSearchHit> candidates = List.of(
                hit(chunkA, docA, "并列分数候选 A", "tie-a", 0.80),
                hit(chunkB, docB, "并列分数候选 B", "tie-b", 0.80),
                hit(chunkC, docC, "并列分数候选 C", "tie-c", 0.80)
        );
        return new Case("E9-相似度并列确定性排序",
                "相似度完全相同情况下按 id 确定性排序，不漂移",
                2, candidates, List.of(chunkA), List.of(docA));
    }

    private static Case longBodyBudgetExceeded() {
        UUID docA = orderedId(301);
        UUID docB = orderedId(302);
        UUID chunkA = orderedId(3011);
        UUID chunkB = orderedId(3021);
        String longTextA = "超长正文内容条款，用于验证正文预算裁剪。".repeat(300); // ~5400 字符
        String longTextB = "第二篇超长正文内容条款，继续测试预算截断。".repeat(300); // ~5700 字符
        List<DocumentSearchHit> candidates = List.of(
                hit(chunkA, docA, longTextA, "long-a", 0.92),
                hit(chunkB, docB, longTextB, "long-b", 0.88)
        );
        return new Case("E10-长正文超出预算边界",
                "总正文远超预算时被全局 MAX_PROMPT_SOURCE_CODE_POINTS 安全截断",
                2, candidates, List.of(chunkA), List.of(docA));
    }

    private static Case multiDocumentCompetitionEightSources() {
        UUID docMajor = orderedId(400);
        List<DocumentSearchHit> candidates = new ArrayList<>();
        // 主文档 4 个高分分块
        for (int i = 0; i < 4; i++) {
            candidates.add(hit(orderedId(4000 + i), docMajor, "主文档高分块 " + i, "major-" + i, 0.95 - i * 0.01));
        }
        // 9 个次文档，各 1 个分块
        UUID docLast = orderedId(409);
        UUID chunkLast = orderedId(4090);
        for (int d = 1; d <= 9; d++) {
            UUID docId = orderedId(400 + d);
            UUID chunkId = orderedId(4000 + d * 10);
            candidates.add(hit(chunkId, docId, "次文档代表 " + d, "minor-" + d, 0.70 - d * 0.02));
        }
        return new Case("E11-中等名额多文档竞争",
                "maxSources = 8，10 个文档，观察代表名额压缩对后序次文档的影响",
                8, candidates, List.of(chunkLast), List.of(docLast));
    }

    private StrategyMetric measure(String strategy, Case testCase, int strategyMode) {
        int limit = testCase.maxSources();
        List<DocumentSearchHit> ordered = switch (strategyMode) {
            case 0 -> SourceCapStrategyExperiment.selectCurrent(testCase.candidates(), limit);
            case 1 -> SourceCapStrategyExperiment.selectRelevanceProtected(testCase.candidates(), limit);
            case 2 -> SourceCapStrategyExperiment.selectConservativeCompromise(testCase.candidates(), limit);
            default -> throw new IllegalArgumentException("Unknown strategy mode: " + strategyMode);
        };
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
        document.put("note", "P4-RAG-QUALITY-01 阶段四：SOURCE_CAP 选择策略对照（确定性，纯函数）。"
                + "A=生产既定两阶段覆盖优先；B=测试侧相关度保护实验策略；C=保守折中方案（保留E6覆盖保证同时消除弱代表占满名额）。"
                + "三者候选输入、source limit、正文预算完全相同；本实验不改变生产默认算法。");
        document.put("maxSources", MAX_SOURCES);
        document.put("experiments", EXPERIMENTS);
        Files.createDirectories(EVIDENCE_PATH.getParent());
        Files.writeString(EVIDENCE_PATH,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document), StandardCharsets.UTF_8);
        System.out.println("SOURCECAP_STRATEGY_EVIDENCE=" + EVIDENCE_PATH.toAbsolutePath());
    }
}
