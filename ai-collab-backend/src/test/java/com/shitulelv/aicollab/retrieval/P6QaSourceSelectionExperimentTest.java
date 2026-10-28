package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-RAG-QUALITY-01 <b>D2：QA 默认 5 来源路径的选择策略确定性对照实验</b>（离线、无外部模型）。
 *
 * <p>四个变体在<b>同一批候选、同一 5 来源名额、同一 8000 code point 预算</b>下对比：</p>
 * <ul>
 * <li><b>A</b>（生产现状）：按候选顺序装配 {@code buildWithDiagnostics(hits, 5)}；</li>
 * <li><b>S1</b>：两阶段跨文档代表优先 {@code buildDiverseWithDiagnostics(hits, 5)}（生产 Agent 入口的选择器）；</li>
 * <li><b>S2</b>（候选）：候选顺序 + 每文档来源名额均衡（cap=2，两遍填充、全量重排后备）；</li>
 * <li><b>S3</b>（对照）：同 S2 但 cap=3，用于量化 cap 取值的边界差异。</li>
 * </ul>
 *
 * <p>S2/S3 只使用候选的 documentId 身份信号（名额计数），不解析 query、不做标题/正文字面匹配，
 * 不属于 P4-RAG-QUALITY-03 已否决的 D/E 字面启发式家族；候选集合、用户 scope、TopK、
 * 来源总数上限与正文预算均不变。</p>
 *
 * <p>两块证据：1) 冻结回放——network-recovery-03 的真实历史候选逐事实 gained/lost；
 * 2) 确定性反例矩阵——跨文档身份、同文档多事实、名额挤占（E7/Q03 模式）、弱干扰（E5 模式）、
 * 确定性、预算、非竞争恒等、阈值回填。矩阵明确记录 S2 的已知结构性权衡：
 * 当单一文档有 ≥3 条必需互补事实且其他文档占据名额时，第 3 条会让位（冻结题集中无此类问题）。</p>
 */
class P6QaSourceSelectionExperimentTest {

    private static final Path EVIDENCE_FILE = Path.of(
            "../docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/network-recovery-03/business-results.json");
    private static final Path OUTPUT = Path.of(
            "target/retrieval-baseline/p6-rag-quality-01/selection-experiments.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int QA_MAX_SOURCES = 5;
    private static final KnowledgeContextBuilder BUILDER = new KnowledgeContextBuilder();
    private static JsonNode rootNode;
    private static final Map<String, List<ExpectedFact>> FACTS = Map.ofEntries(
            Map.entry("Q01", List.of(
                    new ExpectedFact("上线验收规程.md", "自动化验收"),
                    new ExpectedFact("上线验收规程.md", "阻断级缺陷"),
                    new ExpectedFact("上线验收规程.md", "数据迁移"))),
            Map.entry("Q02", List.of(
                    new ExpectedFact("发布回滚预案.md", "只有最近一次回滚演练通过的项目才允许继续发布"))),
            Map.entry("Q03", List.of(
                    new ExpectedFact("上线验收规程.md", "阻断级缺陷"),
                    new ExpectedFact("上线验收规程.md", "两小时"),
                    new ExpectedFact("发布回滚预案.md", "连续两次低于约定阈值"))),
            Map.entry("Q04", List.of(
                    new ExpectedFact("批量导出规范.md", "重新提交"),
                    new ExpectedFact("批量导出规范.md", "发起人、筛选条件、文件大小和下载时间"))),
            Map.entry("Q05", List.of(
                    new ExpectedFact("需求变更单.md", "冻结窗口开始之前"),
                    new ExpectedFact("需求变更单.md", "冻结窗口结束之后"),
                    new ExpectedFact("需求变更单.md", "先完成一次回滚演练"))),
            Map.entry("Q06", List.of(
                    new ExpectedFact("需求变更单.md", "REQ-7741"))),
            Map.entry("Q08", List.of(
                    new ExpectedFact("容量与性能约束.md", "百分之九十五"),
                    new ExpectedFact("容量与性能约束.md", "不得超过五十次"))),
            Map.entry("Q09", List.of(
                    new ExpectedFact("合同模板甲.md", "项目负责人书面批准"),
                    new ExpectedFact("合同模板乙.md", "项目负责人书面批准"))),
            Map.entry("Q10", List.of(
                    new ExpectedFact("合同模板甲.md", "项目负责人书面批准"))),
            Map.entry("Q11", List.of(
                    new ExpectedFact("任务预算规则.md", "禁止修改预算上限"))),
            Map.entry("Q12", List.of(
                    new ExpectedFact("任务预算规则.md", "不得自动超支"))),
            Map.entry("Q13", List.of(
                    new ExpectedFact("不可信资料测试.md", "书面批准"))),
            Map.entry("Q16", List.of(
                    new ExpectedFact("安全与脱敏规范.md", "不能下载文档的原始文件"),
                    new ExpectedFact("安全与脱敏规范.md", "保留首尾各两位"))));

    private record ExpectedFact(String document, String marker) {}

    @BeforeAll
    static void loadEvidence() throws Exception {
        rootNode = JSON.readTree(EVIDENCE_FILE.toFile());
    }

    // ---- 冻结回放：真实历史候选上的逐事实 gained/lost ----

    @Test
    void frozenReplayPerFactGainedAndLost() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        int aRetained = 0, s1Retained = 0, s2Retained = 0, s3Retained = 0, total = 0;
        List<String> s1Regressions = new ArrayList<>();
        List<String> s2Regressions = new ArrayList<>();
        List<String> s3Regressions = new ArrayList<>();
        List<String> s2Gains = new ArrayList<>();

        for (String id : FACTS.keySet().stream().sorted().toList()) {
            List<DocumentSearchHit> candidates = extractCandidates(id);
            if (candidates == null) continue;
            var a = BUILDER.buildWithDiagnostics(candidates, QA_MAX_SOURCES);
            var s1 = BUILDER.buildDiverseWithDiagnostics(candidates, QA_MAX_SOURCES);
            var s2 = BUILDER.buildBalancedWithDiagnostics(candidates, QA_MAX_SOURCES);
            var s3 = BUILDER.buildWithDiagnostics(reorderPerDocumentCap(candidates, QA_MAX_SOURCES, 3), QA_MAX_SOURCES);
            List<Map<String, Object>> factRows = new ArrayList<>();
            for (ExpectedFact fact : FACTS.get(id)) {
                boolean inA = retained(a, fact);
                boolean inS1 = retained(s1, fact);
                boolean inS2 = retained(s2, fact);
                boolean inS3 = retained(s3, fact);
                total++;
                if (inA) aRetained++;
                if (inS1) s1Retained++;
                if (inS2) s2Retained++;
                if (inS3) s3Retained++;
                if (inA && !inS1) s1Regressions.add(id + "/" + fact.marker());
                if (inA && !inS2) s2Regressions.add(id + "/" + fact.marker());
                if (inA && !inS3) s3Regressions.add(id + "/" + fact.marker());
                if (!inA && inS2) s2Gains.add(id + "/" + fact.marker());
                factRows.add(Map.of(
                        "document", fact.document(),
                        "marker", fact.marker(),
                        "A", inA, "S1", inS1, "S2", inS2, "S3", inS3));
            }
            rows.add(Map.of(
                    "id", id,
                    "candidateCount", candidates.size(),
                    "aSources", a.context().sources().size(),
                    "s1Sources", s1.context().sources().size(),
                    "s2Sources", s2.context().sources().size(),
                    "s3Sources", s3.context().sources().size(),
                    "facts", factRows));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluation", "P6-RAG-QUALITY-01 D2 frozen-replay selection comparison on real historical candidates");
        report.put("source", EVIDENCE_FILE.toString());
        report.put("totals", Map.of("facts", total,
                "A", aRetained, "S1", s1Retained, "S2", s2Retained, "S3", s3Retained));
        report.put("s1Regressions", s1Regressions);
        report.put("s2Regressions", s2Regressions);
        report.put("s3Regressions", s3Regressions);
        report.put("s2Gains", s2Gains);
        report.put("rows", rows);
        Files.createDirectories(OUTPUT.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.toFile(), report);

        // D12 准入门槛：S2 在冻结回放上关键事实零回归。
        assertThat(s2Regressions)
                .as("S2 不得在冻结回放中丢失任何 A 已保留的事实（实际丢失: %s）", s2Regressions)
                .isEmpty();
        // 目标缺口改善：历史 Q03 的上线门禁（候选第 6 名被 SOURCE_CAP 挤掉）必须被 S2 救回。
        assertThat(s2Gains).as("S2 必须至少救回历史 Q03 的上线门禁事实").contains("Q03/阻断级缺陷");
        // S1 的已知退化（跨文档代表优先挤掉同文档第二关键事实）必须被如实记录为证据。
        assertThat(s1Regressions).as("S1 应暴露其结构性退化（否则本反例记录失效，需复核）").isNotEmpty();
        // S3 同样零回归（对照记录，供 cap 取值决策）。
        assertThat(s3Regressions).as("S3 对照不得回归").isEmpty();
    }

    // ---- 确定性反例矩阵 ----

    @Test
    void counterexampleMatrix() {
        Map<String, Object> outcomes = new LinkedHashMap<>();
        outcomes.put("CE1-跨文档相同正文身份", ce1_crossDocumentIdenticalBodies());
        outcomes.put("CE2-同文档两条互补事实", ce2_sameDocumentTwoKeyFacts());
        outcomes.put("CE3-同文档三条互补事实(cap边界)", ce3_sameDocumentThreeKeyFacts());
        outcomes.put("CE4-单文档名额洪泛(Q03模式)", ce4_documentFlooding());
        outcomes.put("CE5-弱干扰文档洪泛(E5模式)", ce5_weakDistractorFlood());
        outcomes.put("CE6-确定性", ce6_deterministic());
        outcomes.put("CE7-正文预算", ce7_promptBudget());
        outcomes.put("CE8-非竞争恒等", ce8_identityWithoutCompetition());
        outcomes.put("CE9-阈值回填", ce9_belowThresholdBackfill());
        System.out.println("P6_SELECTION_MATRIX=" + outcomes);
    }

    /** E3 模式：不同文档的相同正文（相同 hash）必须保留两个独立来源身份。 */
    private static Map<String, Object> ce1_crossDocumentIdenticalBodies() {
        List<DocumentSearchHit> hits = new ArrayList<>(List.of(
                hit("合同模板甲.md", "重大变更审批", "重大变更须经项目负责人书面批准后方可实施。", "h1", 0.9),
                hit("合同模板乙.md", "重大变更审批", "重大变更须经项目负责人书面批准后方可实施。", "h1", 0.88)));
        hits.addAll(noise(3));
        var a = BUILDER.buildWithDiagnostics(hits, QA_MAX_SOURCES);
        var s2 = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        for (var built : List.of(a, s2)) {
            assertThat(built.context().sources()).extracting(KnowledgeSource::originalFilename)
                    .as("跨文档相同正文必须保留两份独立来源").contains("合同模板甲.md", "合同模板乙.md");
            assertThat(built.context().sources().get(0).documentId())
                    .isNotEqualTo(built.context().sources().get(1).documentId());
        }
        return Map.of("bothRetained", true);
    }

    /** 同一文档两条互补关键事实（Q16 模式）：cap=2 不得丢失第二条。 */
    private static Map<String, Object> ce2_sameDocumentTwoKeyFacts() {
        List<DocumentSearchHit> hits = new ArrayList<>(List.of(
                hit("安全与脱敏规范.md", "脱敏规则", "只保留首尾各两位。", "h1", 0.9),
                hit("安全与脱敏规范.md", "权限分级", "不能下载文档的原始文件。", "h2", 0.85)));
        hits.addAll(noise(4));
        var s2 = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        assertThat(s2.context().sources()).extracting(KnowledgeSource::heading)
                .contains("脱敏规则", "权限分级");
        return Map.of("bothRetained", true);
    }

    /**
     * S2 的已知结构性权衡：单一文档 3 条必需互补事实 + 多个其他文档占据名额时，
     * 第 3 条事实让位给其他文档代表（A 会保留 3 条）。冻结题集中无此类问题；
     * 该行为作为边界记录，不是门槛失败。
     */
    private static Map<String, Object> ce3_sameDocumentThreeKeyFacts() {
        List<DocumentSearchHit> hits = new ArrayList<>(List.of(
                hit("需求变更单.md", "事实一", "关键事实一必须保留。", "h1", 0.95),
                hit("需求变更单.md", "事实二", "关键事实二必须保留。", "h2", 0.94),
                hit("需求变更单.md", "事实三", "关键事实三必须保留。", "h3", 0.93),
                hit("噪音一.md", "背景", "与问题无关的背景一。", "n1", 0.80),
                hit("噪音二.md", "背景", "与问题无关的背景二。", "n2", 0.75),
                hit("噪音三.md", "背景", "与问题无关的背景三。", "n3", 0.70)));
        var a = BUILDER.buildWithDiagnostics(hits, QA_MAX_SOURCES);
        var s2 = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        var s3 = BUILDER.buildWithDiagnostics(reorderPerDocumentCap(hits, QA_MAX_SOURCES, 3), QA_MAX_SOURCES);
        assertThat(a.context().sources()).extracting(KnowledgeSource::heading).contains("事实三");
        assertThat(s2.context().sources()).extracting(KnowledgeSource::heading)
                .as("S2 结构性权衡：第三条同文档事实让位").doesNotContain("事实三");
        assertThat(s3.context().sources()).extracting(KnowledgeSource::heading)
                .as("S3(cap=3) 保留第三条同文档事实").contains("事实三");
        return Map.of("A-keeps-third", true, "S2-drops-third", true, "S3-keeps-third", true);
    }

    /** Q03 实测模式：单一文档占据候选 2-5 名（名额洪泛），第 6 名关键事实必须被救回。 */
    private static Map<String, Object> ce4_documentFlooding() {
        List<DocumentSearchHit> hits = new ArrayList<>(List.of(
                hit("上线验收规程.md", "灰度观察", "两小时的灰度观察窗口。", "h1", 0.74),
                hit("发布回滚预案.md", "回滚演练", "每个发布周期开始前都要完成一次回滚演练。", "h2", 0.67),
                hit("发布回滚预案.md", "回滚触发条件", "指标连续两次低于约定阈值。", "h3", 0.64),
                hit("发布回滚预案.md", "回滚复核", "回滚完成后必须复核数据完整性。", "h4", 0.63),
                hit("发布回滚预案.md", "续发前提", "超长背景续发前提。", "h5", 0.60),
                hit("上线验收规程.md", "上线门禁", "所有阻断级缺陷必须关闭。", "h6", 0.59)));
        hits.addAll(noise(2));
        var a = BUILDER.buildWithDiagnostics(hits, QA_MAX_SOURCES);
        var s2 = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        assertThat(a.context().sources()).extracting(KnowledgeSource::heading)
                .as("A（现状）在洪泛下丢失第 6 名关键事实").doesNotContain("上线门禁");
        assertThat(s2.context().sources()).extracting(KnowledgeSource::heading)
                .as("S2 必须救回被洪泛挤掉的关键事实")
                .contains("上线门禁", "灰度观察", "回滚触发条件");
        return Map.of("A-loses-gate", true, "S2-recovers-gate", true);
    }

    /** E5 模式：大量弱相关干扰文档与一条强证据并存时，S2 与 A 对强证据行为一致。 */
    private static Map<String, Object> ce5_weakDistractorFlood() {
        List<DocumentSearchHit> hits = new ArrayList<>();
        hits.add(hit("关键文档.md", "关键条款", "唯一强证据条款。", "k1", 0.95));
        for (int index = 0; index < 15; index++) {
            hits.add(hit("干扰文档" + index + ".md", "背景", "弱相关干扰背景内容。", "w" + index, 0.20));
        }
        var a = BUILDER.buildWithDiagnostics(hits, QA_MAX_SOURCES);
        var s2 = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        assertThat(a.context().sources()).extracting(KnowledgeSource::heading).contains("关键条款");
        assertThat(s2.context().sources()).extracting(KnowledgeSource::heading).contains("关键条款");
        assertThat(s2.context().sources().size()).isEqualTo(a.context().sources().size());
        return Map.of("strongKeptByBoth", true);
    }

    /** 相同输入必须产生完全相同的输出顺序（E9 确定性口径）。 */
    private static Map<String, Object> ce6_deterministic() {
        List<DocumentSearchHit> hits = new ArrayList<>(List.of(
                hit("文档一.md", "甲", "内容甲。", "h1", 0.80),
                hit("文档一.md", "乙", "内容乙。", "h2", 0.80),
                hit("文档一.md", "丙", "内容丙。", "h3", 0.80),
                hit("文档二.md", "丁", "内容丁。", "h4", 0.80),
                hit("文档二.md", "戊", "内容戊。", "h5", 0.80),
                hit("文档三.md", "己", "内容己。", "h6", 0.80)));
        var first = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        var second = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        assertThat(first.context().sources()).containsExactlyElementsOf(second.context().sources());
        assertThat(first.context().promptSources()).isEqualTo(second.context().promptSources());
        return Map.of("deterministic", true);
    }

    /** 重排后的装配仍受 8000 code point 硬预算约束（E10 口径）。 */
    private static Map<String, Object> ce7_promptBudget() {
        String body = "很长的正文内容。".repeat(900);
        List<DocumentSearchHit> hits = List.of(
                hit("长文一.md", "附录一", body, "h1", 0.9),
                hit("长文二.md", "附录二", body, "h2", 0.85),
                hit("文档三.md", "条款", "短条款。", "h3", 0.5));
        var s2 = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        assertThat(s2.context().promptSources().codePointCount(0, s2.context().promptSources().length()))
                .isLessThanOrEqualTo(8000);
        return Map.of("withinBudget", true);
    }

    /** 候选数不超过名额时（无竞争），生产 buildBalanced 必须与既有 build 完全同集合同顺序（行为恒等）。 */
    private static Map<String, Object> ce8_identityWithoutCompetition() {
        List<DocumentSearchHit> hits = List.of(
                hit("任务预算规则.md", "预算确认", "禁止修改预算上限。", "h1", 0.9),
                hit("任务预算规则.md", "超限处理", "不得自动超支。", "h2", 0.7),
                hit("其他.md", "背景", "背景。", "h3", 0.4));
        var legacy = BUILDER.buildWithDiagnostics(hits, QA_MAX_SOURCES);
        var balanced = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        assertThat(balanced.context().sources())
                .as("无竞争时必须与既有入口完全一致").containsExactlyElementsOf(legacy.context().sources());
        assertThat(balanced.context().promptSources()).isEqualTo(legacy.context().promptSources());
        return Map.of("identityPreserved", true);
    }

    /** 重排保留全部候选：被 0.15 阈值淘汰的候选不再占用名额，后续候选按序回填。 */
    private static Map<String, Object> ce9_belowThresholdBackfill() {
        List<DocumentSearchHit> hits = List.of(
                hit("文档一.md", "高相关", "关键内容。", "h1", 0.9),
                hit("文档一.md", "低相关", "次要内容。", "h2", 0.10),
                hit("文档二.md", "次级证据", "次级内容。", "h3", 0.5),
                hit("文档三.md", "三级证据", "三级内容。", "h4", 0.45),
                hit("文档四.md", "四级证据", "四级内容。", "h5", 0.4),
                hit("文档五.md", "五级证据", "五级内容。", "h6", 0.35));
        var s2 = BUILDER.buildBalancedWithDiagnostics(hits, QA_MAX_SOURCES);
        assertThat(s2.context().sources()).extracting(KnowledgeSource::heading)
                .as("低于阈值的候选不得进入上下文，名额由后续候选回填")
                .doesNotContain("低相关")
                .contains("高相关", "次级证据", "三级证据", "四级证据", "五级证据");
        assertThat(s2.context().sources()).hasSize(5);
        return Map.of("backfilled", true);
    }

    // ---- S2/S3 测试侧实现（与拟议生产实现逐行一致；生产入库后切换为直接调用生产方法） ----

    /**
     * 每文档名额均衡的两遍重排：第一遍按候选顺序为每文档至多取 cap 条；第二遍按候选顺序补齐剩余名额。
     * 返回值为<b>全量候选</b>的重排（均衡入选者在前、其余候选按原顺序在后），
     * 因此装配阶段的阈值/同文去重/预算淘汰仍保留后备候选回填语义。
     * 候选数不超过名额时恒等返回原顺序（无竞争场景行为与现状逐字节一致）。
     */
    static List<DocumentSearchHit> reorderPerDocumentCap(
            List<DocumentSearchHit> hits, int maxSources, int perDocumentCap) {
        if (hits.size() <= maxSources) {
            return hits;
        }
        List<DocumentSearchHit> balanced = new ArrayList<>(maxSources);
        Map<UUID, Integer> usedByDocument = new HashMap<>();
        java.util.Set<UUID> selected = new java.util.LinkedHashSet<>();
        for (DocumentSearchHit hit : hits) {
            if (balanced.size() == maxSources) break;
            int used = usedByDocument.getOrDefault(hit.documentId(), 0);
            if (used < perDocumentCap) {
                usedByDocument.put(hit.documentId(), used + 1);
                balanced.add(hit);
                selected.add(hit.id());
            }
        }
        for (DocumentSearchHit hit : hits) {
            if (balanced.size() == maxSources) break;
            if (selected.add(hit.id())) {
                balanced.add(hit);
            }
        }
        List<DocumentSearchHit> reordered = new ArrayList<>(hits.size());
        reordered.addAll(balanced);
        for (DocumentSearchHit hit : hits) {
            if (!selected.contains(hit.id())) reordered.add(hit);
        }
        return reordered;
    }

    private static boolean retained(KnowledgeContextBuilder.BuildResult built, ExpectedFact fact) {
        return built.context().sources().stream().anyMatch(source
                -> fact.document().equals(source.originalFilename())
                && source.content() != null
                && source.content().contains(fact.marker()));
    }

    private static final Map<String, UUID> DOCUMENT_IDS = new HashMap<>();

    private static DocumentSearchHit hit(String document, String heading, String content,
                                         String hash, double similarity) {
        UUID documentId = DOCUMENT_IDS.computeIfAbsent(document, ignored -> UUID.randomUUID());
        return new DocumentSearchHit(UUID.randomUUID(), documentId, document, heading,
                content, hash, similarity, Map.of());
    }

    /** 生成 count 条互不相关的中相似度候选（来自互不相同文档），用于撑满候选池。 */
    private static List<DocumentSearchHit> noise(int count) {
        List<DocumentSearchHit> hits = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            hits.add(hit("噪音文档" + index + ".md", "背景" + index,
                    "与当前问题无关的背景说明" + index + "。", "n" + index, 0.30 + index * 0.001));
        }
        return hits;
    }

    private static List<DocumentSearchHit> extractCandidates(String questionId) {
        for (JsonNode row : rootNode.path("rows")) {
            String rowId = row.path("id").asText();
            if (!questionId.equals(rowId) || !row.has("candidates")) continue;
            List<DocumentSearchHit> list = new ArrayList<>();
            for (JsonNode c : row.path("candidates")) {
                list.add(new DocumentSearchHit(
                        UUID.fromString(c.path("id").asText()),
                        UUID.fromString(c.path("documentId").asText()),
                        c.path("originalFilename").asText(),
                        c.path("heading").asText(),
                        c.path("content").asText(),
                        c.path("contentHash").asText(),
                        c.path("similarity").asDouble(),
                        Map.of()));
            }
            return list;
        }
        return null;
    }
}
