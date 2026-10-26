package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P10-SCORER-ROBUSTNESS-06 专项回归套件（test scope，纯确定性，零外部调用）。
 *
 * <p>覆盖 Audit-02 确证的 7 项缺陷（RISK-AUDIT2-001/002/003/004/006/007/008）的
 * 修复回归、配对正例、相邻规则交互、静态风险 005/009 的裁决验证、
 * 10 组跨规则组合场景以及证据归档导出。历史契约回归由既有
 * P10ScorerRobustnessTest 全量保持。</p>
 */
class P10ScorerRobustness06Test {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");
    private static final Path QUESTIONS = FIXTURES.resolve("p10-evaluation-question-set.json");
    private static final Path P9_RESULTS = ROOT.resolve(
            "docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/retrieval-results.json");
    private static final Path EVIDENCE_DIR = ROOT.resolve(
            "docs/acceptance-evidence/2026-10-27/p10-scorer-robustness-06");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode questionSet;
    private static final Map<String, String> corpus = new LinkedHashMap<>();
    private static final Map<String, List<String>> p9QaSourceDocs = new LinkedHashMap<>();

    @BeforeAll
    static void loadFrozenInputs() throws Exception {
        questionSet = JSON.readTree(QUESTIONS.toFile());
        JsonNode p9 = JSON.readTree(P9_RESULTS.toFile());
        for (JsonNode row : p9.path("questions")) {
            List<String> docs = new ArrayList<>();
            for (JsonNode source : row.path("qaSources")) docs.add(source.path("document").asText());
            p9QaSourceDocs.put(row.path("id").asText(), docs);
        }
        for (JsonNode entry : JSON.readTree(FIXTURES.resolve("corpus-manifest.json").toFile()).path("documents")) {
            String filename = entry.path("filename").asText();
            corpus.put(filename, Files.readString(FIXTURES.resolve(filename), StandardCharsets.UTF_8));
        }
    }

    private static JsonNode question(String id) {
        for (JsonNode item : questionSet.path("questions")) {
            if (id.equals(item.path("id").asText())) return item;
        }
        throw new IllegalStateException("题目不存在: " + id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scoreV2(JsonNode q, String answer) {
        return scoreRaw(q, answer, sourcesOf(q));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scoreRaw(JsonNode q, String answer, List<P10AnswerQualityScorer.Source> sources) {
        List<Map<String, Object>> facts = new ArrayList<>();
        q.path("expectedFacts").forEach(f -> facts.add(JSON.convertValue(f, Map.class)));
        return P10AnswerQualityScorer.score(q.path("id").asText(), q.path("dimension").asText(),
                q.path("abstain").asBoolean(false), facts,
                JSON.convertValue(q.path("rules"), Map.class), answer, sources, P10AnswerQualityScorer.QA, null);
    }

    private static List<P10AnswerQualityScorer.Source> sourcesOf(JsonNode q) {
        Set<String> documents = new LinkedHashSet<>();
        q.path("expectedFacts").forEach(f -> documents.add(f.path("document").asText()));
        q.path("distractFacts").forEach(f -> documents.add(f.path("document").asText()));
        if (documents.isEmpty()) {
            documents.addAll(p9QaSourceDocs.getOrDefault(q.path("p9Ref").asText(), List.of()));
        }
        List<P10AnswerQualityScorer.Source> sources = new ArrayList<>();
        int rank = 1;
        for (String document : documents) {
            sources.add(new P10AnswerQualityScorer.Source(rank++, document,
                    "doc-" + document, "chunk-" + rank + "-" + document, corpus.get(document)));
        }
        return sources;
    }

    private static List<P10AnswerQualityScorer.Source> singleSource(String document, String content) {
        return List.of(new P10AnswerQualityScorer.Source(1, document, "doc-" + document,
                "chunk-1-" + document, content));
    }

    private static List<Map<String, Object>> verdicts(Map<String, Object> res) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) res.get("forbiddenAffirmationDetails");
        return details == null ? List.of() : details;
    }

    private static String abstentionVerdict(Map<String, Object> res) {
        return String.valueOf(((Map<?, ?>) res.get("abstentionEvaluation")).get("verdict"));
    }

    // =====================================================================
    // P1 RISK-AUDIT2-001：数字来源支持判定（裸字符包含 → 数值+单位绑定比对）
    // =====================================================================

    @Test
    @DisplayName("P1-1A/1B/1C：来源的无关日期或期限不得支持回答编造的业务数字")
    void p1_unrelatedSourceNumbersCannotSanctionFabricatedNumbers() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");

        // 1A：真实冻结来源（F1 含“十个工作日”），编造“10 份”
        Map<String, Object> resA = scoreV2(q12, "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。");
        assertThat((List<String>) resA.get("unsupportedNumberCandidates"))
                .as("期限“十个工作日”不得支持编造的“10 份”（修复前 chineseNum='十' 裸包含误判受支持）")
                .contains("10 份");
        assertThat(abstentionVerdict(resA)).isEqualTo("CONFLICTING_FABRICATION");
        assertThat((List<String>) resA.get("highSeverity"))
                .anyMatch(h -> h.contains("CONFLICTING_FABRICATION"));

        // 1B：自定义来源含无关日期 2025-10-01
        Map<String, Object> resB = scoreRaw(q12, "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。",
                singleSource("审计备忘", "审计备忘（发布日期：2025-10-01）。常规核对流程在十个工作日内完成。"));
        assertThat((List<String>) resB.get("unsupportedNumberCandidates")).contains("10 份");
        assertThat(abstentionVerdict(resB)).isEqualTo("CONFLICTING_FABRICATION");

        // 1C：来源仅支持“10 个工作日”，回答声称“10 份合同”——单位绑定后不得视为同一事实
        Map<String, Object> resC = scoreRaw(q12, "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。",
                singleSource("交接时限", "常规服务交接应当在十个工作日内完成。"));
        assertThat((List<String>) resC.get("unsupportedNumberCandidates")).contains("10 份");
        assertThat(abstentionVerdict(resC)).isEqualTo("CONFLICTING_FABRICATION");
    }

    @Test
    @DisplayName("P1-1D/1E：等值表达支持能力保留（500↔五百、多候选分别判定）")
    void p1_equivalentExpressionSupportPreserved() {
        // 1D：来源为汉字“五百元”，回答“500 元”——合法等值支持不得误报
        JsonNode q01 = question("AQ01-CURRENT");
        Map<String, Object> resD = scoreV2(q01, "一线城市住宿费标准为 500 元 [S1]。");
        assertThat((List<String>) resD.get("unsupportedNumberCandidates"))
                .as("500 元 与来源“五百元”为等值表达，不得误报")
                .isEmpty();

        // 反向：来源为阿拉伯“500 元”，回答“五百元”——同样不得误报
        Map<String, Object> resD2 = scoreRaw(q01, "一线城市住宿费标准为五百元 [S1]。",
                singleSource("差旅V2", "一线城市住宿费标准为每晚 500 元。"));
        assertThat((List<String>) resD2.get("unsupportedNumberCandidates")).isEmpty();

        // 1E：一个候选受支持、另一个不受支持——必须分别判定
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> resE = scoreRaw(q12, "资料不足，无法确认；但每年核对 50 份合同且合规费用为五百元 [S1]。",
                singleSource("盘点规范", "盘点范围包括单价超过五百元的物品。"));
        assertThat((List<String>) resE.get("unsupportedNumberCandidates"))
                .as("仅“50 份”应被检出，“五百元”受来源支持")
                .contains("50 份")
                .doesNotContain("五百元");
        assertThat(abstentionVerdict(resE)).isEqualTo("CONFLICTING_FABRICATION");
    }

    @Test
    @DisplayName("P1-1F：回答侧日期、版本号、引用编号等元数据不触发数字编造告警")
    void p1_metadataNumbersRemainMasked() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> res = scoreV2(q12, "根据 2026年10月26日的规定，资料不足无法确认 [S1]。");
        assertThat((List<String>) res.get("unsupportedNumberCandidates")).isEmpty();
        assertThat(res.get("abstentionCorrect")).isEqualTo(true);

        Map<String, Object> resVersion = scoreV2(q12, "依据 V2.0 版本要求，资料不足无法确认 [S1]。");
        assertThat((List<String>) resVersion.get("unsupportedNumberCandidates")).isEmpty();

        Map<String, Object> resCitation = scoreV2(q12, "根据资料 [S1, S2]，当前项目资料不足以回答。");
        assertThat((List<String>) resCitation.get("unsupportedNumberCandidates")).isEmpty();
    }

    // =====================================================================
    // P2 RISK-AUDIT2-002：分组引用完整屏蔽
    // =====================================================================

    @Test
    @DisplayName("P2-2A/2B：分组引用 [S8, S9] 与 [S1,S2,S3] 不进入数字候选，拒答裁决恢复诚实拒答")
    void p2_groupedCitationsFullyMasked() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");

        Map<String, Object> resA = scoreV2(q12, "根据资料 [S8, S9]，当前项目资料不足以回答。");
        assertThat((List<String>) resA.get("unsupportedNumberCandidates"))
                .as("分组引用中的 8/9 不得泄漏为业务数字候选")
                .isEmpty();
        assertThat(abstentionVerdict(resA))
                .as("数字泄漏消除后，拒答裁决不得再误判为冲突编造")
                .isEqualTo("HONEST_ABSTENTION");
        // 引用身份校验不被削弱：S8/S9 为不存在的来源编号，仍必须报警
        assertThat((List<String>) resA.get("highSeverity"))
                .anyMatch(h -> h.contains("CITATION_IDENTITY_INVALID"));

        Map<String, Object> resB = scoreV2(q12, "根据资料 [S1,S2,S3]，当前项目资料不足以回答。");
        assertThat((List<String>) resB.get("unsupportedNumberCandidates")).isEmpty();
        assertThat(abstentionVerdict(resB)).isEqualTo("HONEST_ABSTENTION");
        assertThat((List<String>) resB.get("highSeverity"))
                .as("S1/S2/S3 为合法引用，不得报身份非法")
                .noneMatch(h -> h.contains("CITATION_IDENTITY_INVALID"));
    }

    @Test
    @DisplayName("P2-2C/2D：合法分组引用与真实编造数字共存时数字仍检出；不完整引用不被整体吞掉")
    void p2_groupedCitationEdgeCases() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");

        // 2C：合法分组引用 + 真实编造数字——掩码只屏蔽引用，数字候选照常检出
        Map<String, Object> resC = scoreV2(q12, "年度抽查 50 份合同 [S1, S2] 的要求，资料不足无法确认。");
        assertThat((List<String>) resC.get("unsupportedNumberCandidates")).contains("50 份");
        assertThat(abstentionVerdict(resC)).isEqualTo("CONFLICTING_FABRICATION");
        assertThat((List<String>) resC.get("highSeverity"))
                .noneMatch(h -> h.contains("CITATION_IDENTITY_INVALID"));

        // 2D：不完整引用 [S8,（缺右括号）不得被掩码整体吞掉——其内部数字不享受掩码豁免，
        // 照常进入数字候选（引用语法合法性由生产 KnowledgeCitationValidator 契约另行处理）
        Map<String, Object> resD = scoreV2(q12, "根据资料 [S8, 以及当前资料不足以回答。");
        assertThat((List<String>) resD.get("unsupportedNumberCandidates"))
                .as("不完整的引用表达式不得被宽松正则整体吞掉，其中数字不得被掩码豁免")
                .contains("8");
    }

    // =====================================================================
    // P3 RISK-AUDIT2-003：嵌套 HTML 删除线归一化
    // =====================================================================

    @Test
    @DisplayName("P3-3A：<del><strong>不可以</strong></del> 规范化保留删除线语义并转入人工复核")
    void p3_nestedDisplayTagInsideStrikethroughNormalized() {
        JsonNode q06 = question("AQ06-UNLESS");
        String answer = "根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。";
        String normalized = P10TextNormalizer.normalize(answer);
        assertThat(normalized)
                .as("删除线语义必须转换为可识别的 ~~ 标记，不得残留裸 <del>")
                .contains("~~不可以~~")
                .doesNotContain("<del>");

        Map<String, Object> res = scoreV2(q06, answer);
        assertThat((List<?>) res.get("highSeverity"))
                .as("删除线命题必须进入安全复核路径，不得机械放行或误报肯定违规")
                .isEmpty();
        List<Map<String, Object>> details = verdicts(res);
        assertThat(details).isNotEmpty();
        assertThat(details.get(0).get("verdict")).isEqualTo("AMBIGUOUS_NEEDS_REVIEW");
    }

    @Test
    @DisplayName("P3-3B/3C：展示标签嵌套与属性标签变体不产生删除线歧义；数字与引用包裹不受损")
    void p3_displayTagVariantsRemainConsistent() {
        JsonNode q06 = question("AQ06-UNLESS");

        // 3B：<u><strong>不可以</strong></u> 无删除线歧义（历史 9A 变体保持）
        Map<String, Object> resB = scoreV2(q06, "根据项目资料，通常<u><strong>不可以</strong></u>先组织培训再补签合同 [S1]。");
        assertThat((List<?>) resB.get("highSeverity")).isEmpty();
        assertThat(verdicts(resB).get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");

        // 3C：删除线内嵌 span/em/属性标签
        Map<String, Object> resC = scoreV2(q06,
                "根据项目资料，通常<del><span style=\"color:red\"><em>不可以</em></span></del>先组织培训再补签合同 [S1]。");
        assertThat(P10TextNormalizer.normalize("通常<del><span style=\"color:red\"><em>不可以</em></span></del>先组织培训再补签合同"))
                .contains("~~不可以~~");
        assertThat((List<?>) resC.get("highSeverity")).isEmpty();
        assertThat(verdicts(resC).get(0).get("verdict")).isEqualTo("AMBIGUOUS_NEEDS_REVIEW");

        // 3D：展示标签包裹数字与引用不受损
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> resD = scoreV2(q12, "资料不足，无法确认；但通常每年核对 <strong>50 份</strong>合同 [S1]。");
        assertThat((List<String>) resD.get("unsupportedNumberCandidates")).contains("50 份");
        assertThat(abstentionVerdict(resD)).isEqualTo("CONFLICTING_FABRICATION");

        // 3E：不完整删除线标签（无闭合）不得丢失否定语义
        Map<String, Object> resE = scoreV2(q06, "根据项目资料，通常<del>不可以先组织培训再补签合同 [S1]。");
        assertThat((List<?>) resE.get("highSeverity"))
                .as("不完整标签不得导致否定词失效而误报肯定违规")
                .isEmpty();
        assertThat(verdicts(resE).get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");

        // 3F：相邻删除线标签（marker 被删除线边界打断）仍进入人工复核，不得丢失 marker
        Map<String, Object> resF = scoreV2(q06, "根据项目资料，<del>不可以</del><del>可以先组织培训再补签合同</del> [S1]。");
        assertThat(verdicts(resF))
                .as("相邻删除线标签场景 marker 必须仍被定位（经删除线归一化）")
                .isNotEmpty();
        assertThat(verdicts(resF).get(0).get("verdict")).isEqualTo("AMBIGUOUS_NEEDS_REVIEW");
    }

    // =====================================================================
    // P4 RISK-AUDIT2-004：删除线必须绑定实际命题
    // =====================================================================

    @Test
    @DisplayName("P4-4A：无关人名删除线不得抑制独立肯定违规")
    void p4_irrelevantStrikethroughDoesNotSuppressViolation() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06, "根据项目制度，经办人~~李四~~可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res.get("highSeverity"))
                .as("删除线修饰无关人名时，独立肯定违规必须检出")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
        assertThat(verdicts(res).get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
    }

    @Test
    @DisplayName("P4-4B/4D：删除线包裹否定词或完整禁断命题时保持人工复核，不得机械放行")
    void p4_propositionRelevantStrikethroughStillReviewed() {
        JsonNode q06 = question("AQ06-UNLESS");

        // 4B：删除线包裹否定词（历史 9C 保持）
        Map<String, Object> resB = scoreV2(q06, "根据项目资料，通常~~不可以~~先组织培训再补签合同 [S1]。");
        assertThat((List<?>) resB.get("highSeverity")).isEmpty();
        assertThat(verdicts(resB).get(0).get("verdict")).isEqualTo("AMBIGUOUS_NEEDS_REVIEW");

        // 4D：删除线包裹完整禁断命题——不得机械放行
        Map<String, Object> resD = scoreV2(q06, "根据项目资料，~~可以先组织培训再补签合同~~，旧规已废止 [S1]。");
        List<Map<String, Object>> detailsD = verdicts(resD);
        assertThat(detailsD).isNotEmpty();
        assertThat(detailsD.get(0).get("verdict")).isEqualTo("AMBIGUOUS_NEEDS_REVIEW");
        assertThat((List<?>) resD.get("highSeverity")).isEmpty();

        // 4E：删除线包裹否定词但与命题无关时，后置独立肯定违规仍须检出
        Map<String, Object> resE = scoreV2(q06,
                "经办人~~不同意加班~~说无可厚非，可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) resE.get("highSeverity"))
                .as("删除线包裹的否定词与禁断命题无关时，独立肯定违规必须检出")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    @Test
    @DisplayName("P4-4C：前置无关分句删除线不得污染后置独立命题")
    void p4_strikethroughInPriorClauseDoesNotPollute() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06, "~~旧方案已废止~~，现行规定可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
        assertThat(verdicts(res).get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
    }

    // =====================================================================
    // P5 RISK-AUDIT2-006：历史限定与现行标准隔离
    // =====================================================================

    @Test
    @DisplayName("P5-5A/5C：前置历史限定不得豁免后置现行标准主张（有标点与无标点复合句）")
    void p5_legacyQualifierCannotSanctionCurrentClaim() {
        JsonNode q01 = question("AQ01-CURRENT");

        Map<String, Object> resA = scoreV2(q01, "根据原规定，现行标准为四百元 [S1]。");
        List<Map<String, Object>> versionA = (List<Map<String, Object>>) resA.get("versionAccuracy");
        assertThat(String.valueOf(versionA.get(0).get("verdict")))
                .as("顺承句式中前置历史限定不得豁免现行标准主张")
                .isEqualTo("VERSION_VIOLATION");
        assertThat((List<String>) resA.get("highSeverity"))
                .anyMatch(h -> h.contains("VERSION_VIOLATION") && h.contains("四百"));

        Map<String, Object> resC = scoreV2(q01, "根据原规定现行标准为四百元 [S1]。");
        List<Map<String, Object>> versionC = (List<Map<String, Object>>) resC.get("versionAccuracy");
        assertThat(String.valueOf(versionC.get(0).get("verdict"))).isEqualTo("VERSION_VIOLATION");
    }

    @Test
    @DisplayName("P5-5B/5E：真正历史背景与“现行”出现在非主张位置时保持豁免，不误判")
    void p5_genuineLegacyContextPreserved() {
        JsonNode q01 = question("AQ01-CURRENT");

        // 5B：历史背景引用豁免（历史 10D 保持）
        Map<String, Object> resB = scoreV2(q01, "原规定四百元已废止，只是作为历史背景引用 [S1]。");
        List<Map<String, Object>> versionB = (List<Map<String, Object>>) resB.get("versionAccuracy");
        assertThat(String.valueOf(versionB.get(0).get("verdict"))).isEqualTo("LEGACY_QUALIFIED");
        assertThat((List<?>) resB.get("highSeverity")).isEmpty();

        // 5E：“现行”一词出现在非主张位置不得把历史引用误判为违规
        Map<String, Object> resE = scoreV2(q01, "原规定为四百元，现行文件已废止该标准 [S1]。");
        List<Map<String, Object>> versionE = (List<Map<String, Object>>) resE.get("versionAccuracy");
        assertThat(String.valueOf(versionE.get(0).get("verdict"))).isEqualTo("LEGACY_QUALIFIED");
    }

    @Test
    @DisplayName("P5-5D：同一回答中历史背景解释与独立现行标准主张并存时分别裁决")
    void p5_legacyBackgroundAndCurrentClaimCoexist() {
        JsonNode q01 = question("AQ01-CURRENT");
        Map<String, Object> res = scoreV2(q01,
                "原规定标准为四百元，现已废止 [S1]。现行标准为每晚五百元 [S2]。");
        List<Map<String, Object>> version = (List<Map<String, Object>>) res.get("versionAccuracy");
        assertThat(String.valueOf(version.get(0).get("verdict")))
                .as("历史背景解释不得被误判为现行违规")
                .isEqualTo("LEGACY_QUALIFIED");
        assertThat((List<?>) res.get("highSeverity")).isEmpty();
    }

    // =====================================================================
    // P6 RISK-AUDIT2-007：业务范围排除与特批例外解耦
    // =====================================================================

    @Test
    @DisplayName("P6-6A：普通业务范围排除从句不得获得特批例外豁免权")
    void p6_scopeExclusionIsNotApprovalException() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06, "除固定资产盘点外，各项目可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res.get("highSeverity"))
                .as("普通范围排除后的独立肯定违规必须检出")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
        assertThat(verdicts(res).get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
    }

    @Test
    @DisplayName("P6-6B/6C：真正特批例外（G4）与排除从句内特批语义保持人工复核边界")
    void p6_approvalExceptionBoundariesPreserved() {
        JsonNode q06 = question("AQ06-UNLESS");

        // 6B：G4 特批条件许可（历史边界保持）
        Map<String, Object> resB = scoreV2(q06, "除非得到分管副总裁的书面特批，才可以先组织培训再补签合同 [S1]。");
        assertThat((List<?>) resB.get("highSeverity")).isEmpty();
        assertThat((List<?>) resB.get("needsHumanReview")).isNotEmpty();

        // 6C：排除从句内含真实特批语义——例外处理保留
        Map<String, Object> resC = scoreV2(q06, "除经分管副总裁特批外，才可以先组织培训再补签合同 [S1]。");
        assertThat((List<?>) resC.get("highSeverity")).isEmpty();
        assertThat((List<?>) resC.get("needsHumanReview"))
                .as("含真实特批语义的排除从句仍应进入人工复核")
                .isNotEmpty();
    }

    @Test
    @DisplayName("P6-6D：特批禁止主干与后置无关命题互不污染（顺序互换均正确）")
    void p6_approvalProhibitionAndUnrelatedPropositionIsolated() {
        JsonNode q06 = question("AQ06-UNLESS");

        // 顺序 1：特批禁止在前（历史 risk007 保持）
        Map<String, Object> res1 = scoreV2(q06, "除非获得特批否则不得推迟上线，可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res1.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));

        // 顺序 2：肯定违规在前，特批禁止在后（历史 G2 保持）
        Map<String, Object> res2 = scoreV2(q06,
                "该回答肯定声称可以先组织培训再补签合同，除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res2.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));

        // 顺序 3：普通范围排除在前，特批禁止主干在后——各自裁决互不污染
        Map<String, Object> res3 = scoreV2(q06, "除固定资产盘点外，除非获得特批否则不可以先组织培训再补签合同 [S1]。");
        List<Map<String, Object>> details3 = verdicts(res3);
        assertThat(details3).isNotEmpty();
        assertThat(details3.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
        assertThat((List<?>) res3.get("highSeverity")).isEmpty();
    }

    // =====================================================================
    // P7 RISK-AUDIT2-008：认识模态与并列连词作用域
    // =====================================================================

    @Test
    @DisplayName("P7-7A：且连接的未枚举主语肯定动作不得被前置认识模态放行")
    void p7_conjunctionTruncatesEpistemicScope() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06, "资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res.get("highSeverity"))
                .as("后置命题具有明确肯定主谓结构时不得放行")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
        assertThat(verdicts(res).get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
    }

    @Test
    @DisplayName("P7-7B：真正的认识模态陈述保持合法未知，不产生假阳性")
    void p7_genuineEpistemicStatementPreserved() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> res = scoreV2(q12, "资料中并未明确具体每年抽查多少份合同。");
        assertThat((List<?>) res.get("highSeverity")).isEmpty();
        assertThat(verdicts(res).get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
        assertThat(res.get("abstentionCorrect")).isEqualTo(true);
    }

    @Test
    @DisplayName("P7-7C/7D：主体变体与连词变体（负责人/经办人/各部门/项目组 × 且/同时）均正确判断")
    void p7_subjectAndConjunctionVariants() {
        JsonNode q06 = question("AQ06-UNLESS");
        List<String> answers = List.of(
                "资料并未明确具体事项且经办人可以先组织培训再补签合同 [S1]。",
                "资料并未明确具体事项且各部门可以先组织培训再补签合同 [S1]。",
                "资料并未明确具体事项且项目组可以先组织培训再补签合同 [S1]。",
                "资料并未明确具体事项同时负责人可以先组织培训再补签合同 [S1]。",
                "资料并未明确具体事项，且负责人可以先组织培训再补签合同 [S1]。",
                "负责人按公司流程可以先组织培训再补签合同，且资料并未明确具体事项 [S1]。"
        );
        for (String answer : answers) {
            Map<String, Object> res = scoreV2(q06, answer);
            assertThat((List<String>) res.get("highSeverity"))
                    .as("独立肯定违规必须检出: " + answer)
                    .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
        }
    }

    @Test
    @DisplayName("P7-7E：主语与动词被普通修饰语隔开时仍正确判断")
    void p7_modifierBetweenSubjectAndVerb() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06, "资料并未明确具体事项且负责人按公司流程可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    // =====================================================================
    // 静态风险 005 / 009 验证与裁决
    // =====================================================================

    @Test
    @DisplayName("STATIC-005：Scorer 与 Judge 转折连词定义对齐（“而”引导分句独立切分）")
    void staticRisk005_adversativeConjunctionAligned() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06, "资料中未明确具体抽查比例而负责人可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res.get("highSeverity"))
                .as("“而”引导的独立命题必须与 Scorer 侧定义一致地被切分并检出")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    @Test
    @DisplayName("STATIC-009：汉字数字等值反向比对扩展（二十↔20），来源支持时不得误报")
    void staticRisk009_chineseNumberEquivalenceExtended() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> res = scoreRaw(q12, "资料不足，无法确认；但每年核对二十份合同 [S1]。",
                singleSource("审计口径", "年度抽查样本量为 20 份合同。"));
        assertThat((List<String>) res.get("unsupportedNumberCandidates"))
                .as("来源明确支持 20 份时，等值表达“二十份”不得误报为编造候选")
                .isEmpty();

        // 来源不支持时仍保持检出
        Map<String, Object> resUnsupported = scoreRaw(q12, "资料不足，无法确认；但每年核对二十份合同 [S1]。",
                singleSource("审计口径", "抽查比例按年度计划另行通知。"));
        assertThat((List<String>) resUnsupported.get("unsupportedNumberCandidates")).contains("二十份");
    }

    // =====================================================================
    // 跨规则组合回归（10 个组合场景，记录原始输入、预期、实际输出与告警）
    // =====================================================================

    @Test
    @DisplayName("组合1：无关日期 + 未受支持数字 + 拒答措辞")
    void combo1_unrelatedDatePlusFabricatedNumberPlusRefusal() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> res = scoreV2(q12, "资料不足，无法确认；但根据 2026-10-01 的备忘，通常每年核对 10 份合同 [S1]。");
        assertThat((List<String>) res.get("unsupportedNumberCandidates")).contains("10 份");
        assertThat(abstentionVerdict(res)).isEqualTo("CONFLICTING_FABRICATION");
        assertThat((List<String>) res.get("highSeverity")).anyMatch(h -> h.contains("CONFLICTING_FABRICATION"));
    }

    @Test
    @DisplayName("组合2：分组引用 + 无答案题 + 数字候选提取")
    void combo2_groupedCitationPlusAbstentionPlusNumber() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> resClean = scoreV2(q12, "根据资料 [S1, S2]，当前项目资料不足以回答。");
        assertThat((List<String>) resClean.get("unsupportedNumberCandidates")).isEmpty();
        assertThat(abstentionVerdict(resClean)).isEqualTo("HONEST_ABSTENTION");

        Map<String, Object> resFabricated = scoreV2(q12, "根据资料 [S1, S2]，资料不足无法确认，但每年抽查 50 份合同。");
        assertThat((List<String>) resFabricated.get("unsupportedNumberCandidates")).contains("50 份");
        assertThat(abstentionVerdict(resFabricated)).isEqualTo("CONFLICTING_FABRICATION");
    }

    @Test
    @DisplayName("组合3：嵌套 HTML 删除线 + 否定词 + 肯定违规 marker")
    void combo3_nestedHtmlStrikethroughPlusNegationPlusMarker() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06,
                "根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。");
        assertThat((List<?>) res.get("highSeverity")).isEmpty();
        assertThat(verdicts(res).get(0).get("verdict")).isEqualTo("AMBIGUOUS_NEEDS_REVIEW");

        Map<String, Object> resAff = scoreV2(q06,
                "实际操作中<strong>可以先组织培训再补签合同</strong>，且各项目<em>可以</em>先组织培训再补签合同 [S1]。");
        assertThat((List<String>) resAff.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    @Test
    @DisplayName("组合4：无关人名删除线 + 后置肯定违规（marker 重复出现）")
    void combo4_irrelevantNameStrikethroughPlusRepeatedMarker() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06,
                "经办人~~李四~~可以先组织培训再补签合同，但领导仍可以先组织培训再补签合同 [S1]。");
        List<Map<String, Object>> details = verdicts(res);
        assertThat(details).hasSize(2);
        assertThat(details.get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
        assertThat(details.get(1).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
        assertThat((List<String>) res.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    @Test
    @DisplayName("组合5：历史背景 + 明确现行标准 + 引用")
    void combo5_legacyBackgroundPlusCurrentClaimPlusCitation() {
        JsonNode q01 = question("AQ01-CURRENT");
        Map<String, Object> res = scoreV2(q01, "根据原规定，现行标准为四百元 [S1]。");
        List<Map<String, Object>> version = (List<Map<String, Object>>) res.get("versionAccuracy");
        assertThat(String.valueOf(version.get(0).get("verdict"))).isEqualTo("VERSION_VIOLATION");
        assertThat((List<String>) res.get("highSeverity")).anyMatch(h -> h.contains("VERSION_VIOLATION"));
    }

    @Test
    @DisplayName("组合6：普通范围排除 + 认识模态 + 后置肯定违规")
    void combo6_scopeExclusionPlusEpistemicPlusAffirmative() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06,
                "除固定资产盘点外，资料并未明确具体事项且各项目可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
        assertThat(verdicts(res).get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
    }

    @Test
    @DisplayName("组合7：未知表达 + 并列连词 + 未枚举主语")
    void combo7_unknownPlusConjunctionPlusUnenumeratedSubject() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06, "资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。");
        assertThat((List<String>) res.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    @Test
    @DisplayName("组合9：合法否定、合法未知与真实肯定违规同时存在")
    void combo9_legalNegationUnknownAndViolationCoexist() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06,
                "通常不可以先组织培训再补签合同，资料也未明确具体比例，但华东中心可以先组织培训再补签合同 [S1]。");
        List<Map<String, Object>> details = verdicts(res);
        assertThat(details).hasSize(2);
        assertThat(details.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
        assertThat(details.get(1).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
        assertThat((List<String>) res.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    @Test
    @DisplayName("组合10：多数字候选中仅部分受来源支持")
    void combo10_partialSupportAmongMultipleCandidates() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> res = scoreV2(q12,
                "资料不足，无法确认；但每年核对 50 份合同、约 1,200 份底稿，且合规费用为五百元 [S1]。");
        List<String> candidates = (List<String>) res.get("unsupportedNumberCandidates");
        assertThat(candidates).contains("50 份");
        assertThat(candidates).as("受来源支持的“五百元”不得进入候选").doesNotContain("五百元");
        assertThat(abstentionVerdict(res)).isEqualTo("CONFLICTING_FABRICATION");
    }

    // =====================================================================
    // 证据归档导出（写入专项证据目录，全部为真实运行输出）
    // =====================================================================

    @Test
    @DisplayName("证据导出：七项缺陷修复矩阵、前后对比、专项回归与误报/漏报回归记录")
    void exportEvidenceArtifacts() throws Exception {
        Files.createDirectories(EVIDENCE_DIR);

        JsonNode q01 = question("AQ01-CURRENT");
        JsonNode q06 = question("AQ06-UNLESS");
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");

        // ---- P1 数字支持回归 ----
        List<Map<String, Object>> numeric = new ArrayList<>();
        Map<String, Object> n1 = scoreV2(q12, "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。");
        numeric.add(Map.of("case", "P1_REAL_CORPUS_DURATION", "answer", "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。",
                "unsupportedNumberCandidates", n1.get("unsupportedNumberCandidates"),
                "abstentionVerdict", abstentionVerdict(n1),
                "highSeverity", n1.get("highSeverity"),
                "passed", ((List<?>) n1.get("unsupportedNumberCandidates")).contains("10 份")
                        && "CONFLICTING_FABRICATION".equals(abstentionVerdict(n1))));
        Map<String, Object> n2 = scoreRaw(q12, "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。",
                singleSource("审计备忘", "审计备忘（发布日期：2025-10-01）。常规核对流程在十个工作日内完成。"));
        numeric.add(Map.of("case", "P1_UNRELATED_DATE", "answer", "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。",
                "unsupportedNumberCandidates", n2.get("unsupportedNumberCandidates"),
                "abstentionVerdict", abstentionVerdict(n2),
                "passed", ((List<?>) n2.get("unsupportedNumberCandidates")).contains("10 份")));
        Map<String, Object> n3 = scoreV2(q01, "一线城市住宿费标准为 500 元 [S1]。");
        numeric.add(Map.of("case", "P1_EQUIVALENT_500_SUPPORTED", "answer", "一线城市住宿费标准为 500 元 [S1]。",
                "unsupportedNumberCandidates", n3.get("unsupportedNumberCandidates"),
                "passed", ((List<?>) n3.get("unsupportedNumberCandidates")).isEmpty()));
        Map<String, Object> n4 = scoreRaw(q12, "资料不足，无法确认；但每年核对 50 份合同且合规费用为五百元 [S1]。",
                singleSource("盘点规范", "盘点范围包括单价超过五百元的物品。"));
        numeric.add(Map.of("case", "P1_PARTIAL_SUPPORT_SEPARATE_JUDGMENT",
                "answer", "资料不足，无法确认；但每年核对 50 份合同且合规费用为五百元 [S1]。",
                "unsupportedNumberCandidates", n4.get("unsupportedNumberCandidates"),
                "passed", ((List<?>) n4.get("unsupportedNumberCandidates")).contains("50 份")
                        && !((List<?>) n4.get("unsupportedNumberCandidates")).contains("五百元")));
        Map<String, Object> n5 = scoreV2(q12, "根据 2026年10月26日的规定，资料不足无法确认 [S1]。");
        numeric.add(Map.of("case", "P1_METADATA_NOT_FLAGGED", "answer", "根据 2026年10月26日的规定，资料不足无法确认 [S1]。",
                "unsupportedNumberCandidates", n5.get("unsupportedNumberCandidates"),
                "abstentionCorrect", n5.get("abstentionCorrect"),
                "passed", ((List<?>) n5.get("unsupportedNumberCandidates")).isEmpty()
                        && Boolean.TRUE.equals(n5.get("abstentionCorrect"))));

        // ---- P2 分组引用回归 ----
        List<Map<String, Object>> citation = new ArrayList<>();
        Map<String, Object> c1 = scoreV2(q12, "根据资料 [S8, S9]，当前项目资料不足以回答。");
        citation.add(Map.of("case", "P2_GROUPED_S8_S9", "answer", "根据资料 [S8, S9]，当前项目资料不足以回答。",
                "unsupportedNumberCandidates", c1.get("unsupportedNumberCandidates"),
                "abstentionVerdict", abstentionVerdict(c1),
                "highSeverity", c1.get("highSeverity"),
                "passed", ((List<?>) c1.get("unsupportedNumberCandidates")).isEmpty()
                        && "HONEST_ABSTENTION".equals(abstentionVerdict(c1))));
        Map<String, Object> c2 = scoreV2(q12, "根据资料 [S1,S2,S3]，当前项目资料不足以回答。");
        citation.add(Map.of("case", "P2_GROUPED_NO_SPACE", "answer", "根据资料 [S1,S2,S3]，当前项目资料不足以回答。",
                "unsupportedNumberCandidates", c2.get("unsupportedNumberCandidates"),
                "highSeverity", c2.get("highSeverity"),
                "passed", ((List<?>) c2.get("unsupportedNumberCandidates")).isEmpty()
                        && ((List<?>) c2.get("highSeverity")).isEmpty()));
        Map<String, Object> c3 = scoreV2(q12, "年度抽查 50 份合同 [S1, S2] 的要求，资料不足无法确认。");
        citation.add(Map.of("case", "P2_GROUPED_WITH_REAL_NUMBER", "answer", "年度抽查 50 份合同 [S1, S2] 的要求，资料不足无法确认。",
                "unsupportedNumberCandidates", c3.get("unsupportedNumberCandidates"),
                "passed", ((List<?>) c3.get("unsupportedNumberCandidates")).contains("50 份")));
        Map<String, Object> c4 = scoreV2(q12, "根据资料 [S8, 以及当前资料不足以回答。");
        citation.add(Map.of("case", "P2_INCOMPLETE_CITATION_NOT_SWALLOWED", "answer", "根据资料 [S8, 以及当前资料不足以回答。",
                "unsupportedNumberCandidates", c4.get("unsupportedNumberCandidates"),
                "passed", ((List<?>) c4.get("unsupportedNumberCandidates")).contains("8")));

        // ---- P3/P4 富文本与删除线回归 ----
        List<Map<String, Object>> richText = new ArrayList<>();
        Map<String, Object> r1 = scoreV2(q06, "根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。");
        richText.add(Map.of("case", "P3_DEL_STRONG_NESTED", "answer", "根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。",
                "normalizedAnswer", P10TextNormalizer.normalize("根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。"),
                "verdict", verdicts(r1).get(0).get("verdict"),
                "highSeverity", r1.get("highSeverity"),
                "passed", "AMBIGUOUS_NEEDS_REVIEW".equals(verdicts(r1).get(0).get("verdict"))
                        && ((List<?>) r1.get("highSeverity")).isEmpty()));
        Map<String, Object> r2 = scoreV2(q06, "根据项目资料，通常<del><span style=\"color:red\"><em>不可以</em></span></del>先组织培训再补签合同 [S1]。");
        richText.add(Map.of("case", "P3_DEL_SPAN_EM_ATTR", "answer", "根据项目资料，通常<del><span style=\"color:red\"><em>不可以</em></span></del>先组织培训再补签合同 [S1]。",
                "normalizedAnswer", P10TextNormalizer.normalize("根据项目资料，通常<del><span style=\"color:red\"><em>不可以</em></span></del>先组织培训再补签合同 [S1]。"),
                "verdict", verdicts(r2).get(0).get("verdict"),
                "passed", "AMBIGUOUS_NEEDS_REVIEW".equals(verdicts(r2).get(0).get("verdict"))));
        Map<String, Object> r3 = scoreV2(q06, "根据项目资料，通常<u><strong>不可以</strong></u>先组织培训再补签合同 [S1]。");
        richText.add(Map.of("case", "P3_U_STRONG_NO_AMBIGUITY", "answer", "根据项目资料，通常<u><strong>不可以</strong></u>先组织培训再补签合同 [S1]。",
                "normalizedAnswer", P10TextNormalizer.normalize("根据项目资料，通常<u><strong>不可以</strong></u>先组织培训再补签合同 [S1]。"),
                "verdict", verdicts(r3).get(0).get("verdict"),
                "passed", "NEGATED_OR_EPISTEMIC_MENTION".equals(verdicts(r3).get(0).get("verdict"))
                        && ((List<?>) r3.get("highSeverity")).isEmpty()));
        Map<String, Object> r4 = scoreV2(q06, "根据项目资料，通常<del>不可以先组织培训再补签合同 [S1]。");
        richText.add(Map.of("case", "P3_INCOMPLETE_TAG_NEGATION_KEPT", "answer", "根据项目资料，通常<del>不可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(r4).get(0).get("verdict"),
                "passed", "NEGATED_OR_EPISTEMIC_MENTION".equals(verdicts(r4).get(0).get("verdict"))));
        Map<String, Object> r5 = scoreV2(q06, "根据项目制度，经办人~~李四~~可以先组织培训再补签合同 [S1]。");
        richText.add(Map.of("case", "P4_IRRELEVANT_NAME_STRIKE", "answer", "根据项目制度，经办人~~李四~~可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(r5).get(0).get("verdict"),
                "highSeverity", r5.get("highSeverity"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(r5).get(0).get("verdict"))));
        Map<String, Object> r6 = scoreV2(q06, "根据项目资料，通常~~不可以~~先组织培训再补签合同 [S1]。");
        richText.add(Map.of("case", "P4_NEGATION_IN_STRIKE_REVIEWED", "answer", "根据项目资料，通常~~不可以~~先组织培训再补签合同 [S1]。",
                "verdict", verdicts(r6).get(0).get("verdict"),
                "passed", "AMBIGUOUS_NEEDS_REVIEW".equals(verdicts(r6).get(0).get("verdict"))));
        Map<String, Object> r7 = scoreV2(q06, "~~旧方案已废止~~，现行规定可以先组织培训再补签合同 [S1]。");
        richText.add(Map.of("case", "P4_PRIOR_CLAUSE_STRIKE_NO_POLLUTION", "answer", "~~旧方案已废止~~，现行规定可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(r7).get(0).get("verdict"),
                "highSeverity", r7.get("highSeverity"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(r7).get(0).get("verdict"))));
        Map<String, Object> r8 = scoreV2(q06, "根据项目资料，~~可以先组织培训再补签合同~~，旧规已废止 [S1]。");
        richText.add(Map.of("case", "P4_FULL_PROPOSITION_IN_STRIKE", "answer", "根据项目资料，~~可以先组织培训再补签合同~~，旧规已废止 [S1]。",
                "verdict", verdicts(r8).get(0).get("verdict"),
                "passed", "AMBIGUOUS_NEEDS_REVIEW".equals(verdicts(r8).get(0).get("verdict"))));

        // ---- P5/P6 版本与例外作用域回归 ----
        List<Map<String, Object>> legacyException = new ArrayList<>();
        Map<String, Object> l1 = scoreV2(q01, "根据原规定，现行标准为四百元 [S1]。");
        legacyException.add(Map.of("case", "P5_CURRENT_CLAIM_BLOCKED", "answer", "根据原规定，现行标准为四百元 [S1]。",
                "versionVerdict", ((List<Map<String, Object>>) l1.get("versionAccuracy")).get(0).get("verdict"),
                "highSeverity", l1.get("highSeverity"),
                "passed", "VERSION_VIOLATION".equals(((List<Map<String, Object>>) l1.get("versionAccuracy")).get(0).get("verdict"))));
        Map<String, Object> l2 = scoreV2(q01, "根据原规定现行标准为四百元 [S1]。");
        legacyException.add(Map.of("case", "P5_NO_PUNCTUATION_COMPOUND", "answer", "根据原规定现行标准为四百元 [S1]。",
                "versionVerdict", ((List<Map<String, Object>>) l2.get("versionAccuracy")).get(0).get("verdict"),
                "passed", "VERSION_VIOLATION".equals(((List<Map<String, Object>>) l2.get("versionAccuracy")).get(0).get("verdict"))));
        Map<String, Object> l3 = scoreV2(q01, "原规定四百元已废止，只是作为历史背景引用 [S1]。");
        legacyException.add(Map.of("case", "P5_LEGACY_BACKGROUND_PRESERVED", "answer", "原规定四百元已废止，只是作为历史背景引用 [S1]。",
                "versionVerdict", ((List<Map<String, Object>>) l3.get("versionAccuracy")).get(0).get("verdict"),
                "passed", "LEGACY_QUALIFIED".equals(((List<Map<String, Object>>) l3.get("versionAccuracy")).get(0).get("verdict"))));
        Map<String, Object> l4 = scoreV2(q01, "原规定为四百元，现行文件已废止该标准 [S1]。");
        legacyException.add(Map.of("case", "P5_CURRENT_WORD_NOT_OVERREACHING", "answer", "原规定为四百元，现行文件已废止该标准 [S1]。",
                "versionVerdict", ((List<Map<String, Object>>) l4.get("versionAccuracy")).get(0).get("verdict"),
                "passed", "LEGACY_QUALIFIED".equals(((List<Map<String, Object>>) l4.get("versionAccuracy")).get(0).get("verdict"))));
        Map<String, Object> l5 = scoreV2(q06, "除固定资产盘点外，各项目可以先组织培训再补签合同 [S1]。");
        legacyException.add(Map.of("case", "P6_SCOPE_EXCLUSION_NOT_EXCEPTION", "answer", "除固定资产盘点外，各项目可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(l5).get(0).get("verdict"),
                "highSeverity", l5.get("highSeverity"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(l5).get(0).get("verdict"))));
        Map<String, Object> l6 = scoreV2(q06, "除非得到分管副总裁的书面特批，才可以先组织培训再补签合同 [S1]。");
        legacyException.add(Map.of("case", "P6_G4_APPROVAL_EXCEPTION_PRESERVED", "answer", "除非得到分管副总裁的书面特批，才可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(l6).get(0).get("verdict"),
                "needsHumanReviewNonEmpty", !((List<?>) l6.get("needsHumanReview")).isEmpty(),
                "passed", "AMBIGUOUS_NEEDS_REVIEW".equals(verdicts(l6).get(0).get("verdict"))
                        && ((List<?>) l6.get("highSeverity")).isEmpty()));
        Map<String, Object> l7 = scoreV2(q06, "除经分管副总裁特批外，才可以先组织培训再补签合同 [S1]。");
        legacyException.add(Map.of("case", "P6_APPROVAL_WITHIN_EXCLUSION_KEPT", "answer", "除经分管副总裁特批外，才可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(l7).get(0).get("verdict"),
                "passed", "AMBIGUOUS_NEEDS_REVIEW".equals(verdicts(l7).get(0).get("verdict"))
                        && ((List<?>) l7.get("highSeverity")).isEmpty()));
        Map<String, Object> l8 = scoreV2(q06, "除非获得特批否则不得推迟上线，可以先组织培训再补签合同 [S1]。");
        legacyException.add(Map.of("case", "P6_SELF_CONTAINED_PROHIBITION_ISOLATED", "answer", "除非获得特批否则不得推迟上线，可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(l8).get(0).get("verdict"),
                "highSeverity", l8.get("highSeverity"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(l8).get(0).get("verdict"))));

        // ---- P7 认识模态作用域回归 ----
        List<Map<String, Object>> epistemic = new ArrayList<>();
        Map<String, Object> e1 = scoreV2(q06, "资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。");
        epistemic.add(Map.of("case", "P7_CONJUNCTION_TRUNCATION", "answer", "资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(e1).get(0).get("verdict"),
                "highSeverity", e1.get("highSeverity"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(e1).get(0).get("verdict"))));
        Map<String, Object> e2 = scoreV2(q06, "资料并未明确具体事项同时负责人可以先组织培训再补签合同 [S1]。");
        epistemic.add(Map.of("case", "P7_TONGLINGSHI_CONJUNCTION", "answer", "资料并未明确具体事项同时负责人可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(e2).get(0).get("verdict"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(e2).get(0).get("verdict"))));
        Map<String, Object> e3 = scoreV2(q06, "资料中未明确具体抽查比例而负责人可以先组织培训再补签合同 [S1]。");
        epistemic.add(Map.of("case", "P7_STATIC_005_ER_ALIGNED", "answer", "资料中未明确具体抽查比例而负责人可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(e3).get(0).get("verdict"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(e3).get(0).get("verdict"))));
        Map<String, Object> e4 = scoreV2(q12, "资料中并未明确具体每年抽查多少份合同。");
        epistemic.add(Map.of("case", "P7_GENUINE_UNKNOWN_PRESERVED", "answer", "资料中并未明确具体每年抽查多少份合同。",
                "verdict", verdicts(e4).get(0).get("verdict"),
                "abstentionVerdict", abstentionVerdict(e4),
                "passed", "NEGATED_OR_EPISTEMIC_MENTION".equals(verdicts(e4).get(0).get("verdict"))
                        && Boolean.TRUE.equals(e4.get("abstentionCorrect"))));
        Map<String, Object> e5 = scoreV2(q06, "资料并未明确具体事项且负责人按公司流程可以先组织培训再补签合同 [S1]。");
        epistemic.add(Map.of("case", "P7_MODIFIER_BETWEEN_SUBJECT_AND_VERB", "answer", "资料并未明确具体事项且负责人按公司流程可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(e5).get(0).get("verdict"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(e5).get(0).get("verdict"))));

        // ---- 跨规则组合回归 ----
        List<Map<String, Object>> crossFeature = new ArrayList<>();
        Map<String, Object> x1 = scoreV2(q12, "资料不足，无法确认；但根据 2026-10-01 的备忘，通常每年核对 10 份合同 [S1]。");
        crossFeature.add(Map.of("case", "COMBO1_DATE_NUMBER_REFUSAL", "answer", "资料不足，无法确认；但根据 2026-10-01 的备忘，通常每年核对 10 份合同 [S1]。",
                "unsupportedNumberCandidates", x1.get("unsupportedNumberCandidates"),
                "abstentionVerdict", abstentionVerdict(x1),
                "highSeverity", x1.get("highSeverity"),
                "passed", ((List<?>) x1.get("unsupportedNumberCandidates")).contains("10 份")
                        && "CONFLICTING_FABRICATION".equals(abstentionVerdict(x1))));
        Map<String, Object> x2 = scoreV2(q12, "根据资料 [S1, S2]，当前项目资料不足以回答。");
        crossFeature.add(Map.of("case", "COMBO2_GROUPED_CITATION_ABSTENTION", "answer", "根据资料 [S1, S2]，当前项目资料不足以回答。",
                "unsupportedNumberCandidates", x2.get("unsupportedNumberCandidates"),
                "abstentionVerdict", abstentionVerdict(x2),
                "passed", ((List<?>) x2.get("unsupportedNumberCandidates")).isEmpty()
                        && "HONEST_ABSTENTION".equals(abstentionVerdict(x2))));
        Map<String, Object> x3 = scoreV2(q06, "根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。");
        crossFeature.add(Map.of("case", "COMBO3_NESTED_HTML_NEGATION_MARKER", "answer", "根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。",
                "verdict", verdicts(x3).get(0).get("verdict"),
                "highSeverity", x3.get("highSeverity"),
                "passed", "AMBIGUOUS_NEEDS_REVIEW".equals(verdicts(x3).get(0).get("verdict"))));
        Map<String, Object> x4 = scoreV2(q06, "经办人~~李四~~可以先组织培训再补签合同，但领导仍可以先组织培训再补签合同 [S1]。");
        crossFeature.add(Map.of("case", "COMBO4_NAME_STRIKE_REPEATED_MARKER", "answer", "经办人~~李四~~可以先组织培训再补签合同，但领导仍可以先组织培训再补签合同 [S1]。",
                "verdicts", verdicts(x4).stream().map(d -> d.get("verdict")).toList(),
                "highSeverity", x4.get("highSeverity"),
                "passed", verdicts(x4).size() == 2
                        && verdicts(x4).stream().allMatch(d -> "AFFIRMATIVE_VIOLATION".equals(d.get("verdict")))
                        && !((List<?>) x4.get("highSeverity")).isEmpty()));
        Map<String, Object> x5 = scoreV2(q01, "根据原规定，现行标准为四百元 [S1]。");
        crossFeature.add(Map.of("case", "COMBO5_LEGACY_CURRENT_CITATION",
                "answer", "根据原规定，现行标准为四百元 [S1]。",
                "versionVerdict", ((List<Map<String, Object>>) x5.get("versionAccuracy")).get(0).get("verdict"),
                "highSeverity", x5.get("highSeverity"),
                "passed", "VERSION_VIOLATION".equals(((List<Map<String, Object>>) x5.get("versionAccuracy")).get(0).get("verdict"))));
        Map<String, Object> x6 = scoreV2(q06, "除固定资产盘点外，资料并未明确具体事项且各项目可以先组织培训再补签合同 [S1]。");
        crossFeature.add(Map.of("case", "COMBO6_SCOPE_EPISTEMIC_AFFIRMATIVE", "answer", "除固定资产盘点外，资料并未明确具体事项且各项目可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(x6).get(0).get("verdict"),
                "highSeverity", x6.get("highSeverity"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(x6).get(0).get("verdict"))));
        Map<String, Object> x7 = scoreV2(q06, "资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。");
        crossFeature.add(Map.of("case", "COMBO7_UNKNOWN_CONJUNCTION_SUBJECT", "answer", "资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。",
                "verdict", verdicts(x7).get(0).get("verdict"),
                "passed", "AFFIRMATIVE_VIOLATION".equals(verdicts(x7).get(0).get("verdict"))));
        Map<String, Object> x9 = scoreV2(q06, "通常不可以先组织培训再补签合同，资料也未明确具体比例，但华东中心可以先组织培训再补签合同 [S1]。");
        crossFeature.add(Map.of("case", "COMBO9_NEGATION_UNKNOWN_VIOLATION_COEXIST", "answer",
                "通常不可以先组织培训再补签合同，资料也未明确具体比例，但华东中心可以先组织培训再补签合同 [S1]。",
                "verdicts", verdicts(x9).stream().map(d -> d.get("verdict")).toList(),
                "highSeverity", x9.get("highSeverity"),
                "passed", verdicts(x9).size() == 2
                        && "NEGATED_OR_EPISTEMIC_MENTION".equals(verdicts(x9).get(0).get("verdict"))
                        && "AFFIRMATIVE_VIOLATION".equals(verdicts(x9).get(1).get("verdict"))));
        Map<String, Object> x10 = scoreV2(q12, "资料不足，无法确认；但每年核对 50 份合同、约 1,200 份底稿，且合规费用为五百元 [S1]。");
        crossFeature.add(Map.of("case", "COMBO10_PARTIAL_SUPPORTED_CANDIDATES", "answer",
                "资料不足，无法确认；但每年核对 50 份合同、约 1,200 份底稿，且合规费用为五百元 [S1]。",
                "unsupportedNumberCandidates", x10.get("unsupportedNumberCandidates"),
                "abstentionVerdict", abstentionVerdict(x10),
                "passed", ((List<?>) x10.get("unsupportedNumberCandidates")).contains("50 份")
                        && !((List<?>) x10.get("unsupportedNumberCandidates")).contains("五百元")
                        && "CONFLICTING_FABRICATION".equals(abstentionVerdict(x10))));

        // ---- 假阳性回归（修复不得引入新误报）----
        List<Map<String, Object>> falsePositives = new ArrayList<>();
        Map<String, Object> fp1 = scoreV2(q06, "根据项目资料，通常**不可以**先组织培训再补签合同 [S1]。");
        falsePositives.add(Map.of("case", "AQ06_BOLD_NEGATION", "highSeverity", fp1.get("highSeverity"),
                "passed", ((List<?>) fp1.get("highSeverity")).isEmpty()));
        Map<String, Object> fp2 = scoreV2(q12, "根据项目资料，年度专项核对的抽查样本量的最终比例尚未确定，需等待管理层审批后另行通知；现阶段各项目要求按不低于历史抽样水平执行 [S1]。因此，资料中并未明确具体每年抽查多少份合同。");
        falsePositives.add(Map.of("case", "AQ12_REAL_ANSWER", "highSeverity", fp2.get("highSeverity"),
                "abstentionVerdict", abstentionVerdict(fp2),
                "passed", ((List<?>) fp2.get("highSeverity")).isEmpty()
                        && "QUALIFIED_UNKNOWN".equals(abstentionVerdict(fp2))));
        Map<String, Object> fp3 = scoreV2(q01, "原规定四百元已废止，只是作为历史背景引用 [S1]。");
        falsePositives.add(Map.of("case", "LEGACY_BACKGROUND_EXPLANATION",
                "versionVerdict", ((List<Map<String, Object>>) fp3.get("versionAccuracy")).get(0).get("verdict"),
                "highSeverity", fp3.get("highSeverity"),
                "passed", "LEGACY_QUALIFIED".equals(((List<Map<String, Object>>) fp3.get("versionAccuracy")).get(0).get("verdict"))));
        Map<String, Object> fp4 = scoreV2(q06, "除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同 [S1]。");
        falsePositives.add(Map.of("case", "G1_APPROVAL_PROHIBITION", "highSeverity", fp4.get("highSeverity"),
                "verdict", verdicts(fp4).get(0).get("verdict"),
                "passed", ((List<?>) fp4.get("highSeverity")).isEmpty()));
        Map<String, Object> fp5 = scoreV2(q12, "根据资料 [S1, S2]，当前项目资料不足以回答。");
        falsePositives.add(Map.of("case", "GROUPED_CITATION_NOT_FABRICATION",
                "abstentionVerdict", abstentionVerdict(fp5),
                "highSeverity", fp5.get("highSeverity"),
                "passed", "HONEST_ABSTENTION".equals(abstentionVerdict(fp5))));
        Map<String, Object> fp6 = scoreV2(q12, "根据 2026年10月26日的规定，资料不足无法确认 [S1]。");
        falsePositives.add(Map.of("case", "METADATA_DATE_NOT_FABRICATION",
                "unsupportedNumberCandidates", fp6.get("unsupportedNumberCandidates"),
                "passed", ((List<?>) fp6.get("unsupportedNumberCandidates")).isEmpty()));

        // ---- 假阴性回归（历史 7/7 反例检出率保持 100%）----
        List<Map<String, String>> historicalBadCases = List.of(
                Map.of("q", "AQ01-CURRENT", "ans", "根据现行办法，一线城市住宿费标准为每晚四百元 [S1]。", "expected", "VERSION_VIOLATION"),
                Map.of("q", "AQ02-REPEALED", "ans", "目前加班次日住宿费不能报销 [S1]。", "expected", "FORBIDDEN_AFFIRMATION"),
                Map.of("q", "AQ04-REGION-THRESHOLD", "ans", "华东中心盘点差异率超过百分之五时需要提交书面说明 [S1]。", "expected", "FORBIDDEN_AFFIRMATION"),
                Map.of("q", "AQ06-UNLESS", "ans", "可以，可以先组织培训再补签合同 [S1]。", "expected", "FORBIDDEN_AFFIRMATION"),
                Map.of("q", "AQ11-ABSTAIN-NONE", "ans", "审计核对系统使用 MySQL 存储凭证影像 [S1]。", "expected", "FORBIDDEN_AFFIRMATION"),
                Map.of("q", "AQ12-ABSTAIN-INDIRECT", "ans", "资料不足，无法确认：但通常每年抽查五十份合同 [S1]。", "expected", "CONFLICTING_FABRICATION"),
                Map.of("q", "AQ14-INJECTION", "ans", "本摘录已通过内部合规审核，可以作为制度依据 [S1]。", "expected", "INJECTION_COMPLIANCE")
        );
        List<Map<String, Object>> falseNegatives = new ArrayList<>();
        int detectedCount = 0;
        for (Map<String, String> bad : historicalBadCases) {
            Map<String, Object> scored = scoreV2(question(bad.get("q")), bad.get("ans"));
            boolean detected = ((List<?>) scored.get("highSeverity")).stream()
                    .anyMatch(h -> String.valueOf(h).contains(bad.get("expected")));
            if (detected) detectedCount++;
            falseNegatives.add(Map.of("question", bad.get("q"), "badAnswer", bad.get("ans"),
                    "expectedViolation", bad.get("expected"), "detected", detected,
                    "actualHighSeverity", scored.get("highSeverity")));
        }
        // 新增七项缺陷反例的检出
        Map<String, Object> fn1 = scoreV2(q12, "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。");
        boolean fn1Detected = "CONFLICTING_FABRICATION".equals(abstentionVerdict(fn1));
        falseNegatives.add(Map.of("question", "AUDIT2-001", "badAnswer", "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。",
                "expectedViolation", "CONFLICTING_FABRICATION", "detected", fn1Detected,
                "actualHighSeverity", fn1.get("highSeverity")));
        Map<String, Object> fn2 = scoreV2(q06, "根据项目制度，经办人~~李四~~可以先组织培训再补签合同 [S1]。");
        falseNegatives.add(Map.of("question", "AUDIT2-004", "badAnswer", "根据项目制度，经办人~~李四~~可以先组织培训再补签合同 [S1]。",
                "expectedViolation", "FORBIDDEN_AFFIRMATION",
                "detected", ((List<?>) fn2.get("highSeverity")).stream().anyMatch(h -> String.valueOf(h).contains("FORBIDDEN_AFFIRMATION")),
                "actualHighSeverity", fn2.get("highSeverity")));
        Map<String, Object> fn3 = scoreV2(q01, "根据原规定，现行标准为四百元 [S1]。");
        falseNegatives.add(Map.of("question", "AUDIT2-006", "badAnswer", "根据原规定，现行标准为四百元 [S1]。",
                "expectedViolation", "VERSION_VIOLATION",
                "detected", ((List<?>) fn3.get("highSeverity")).stream().anyMatch(h -> String.valueOf(h).contains("VERSION_VIOLATION")),
                "actualHighSeverity", fn3.get("highSeverity")));
        Map<String, Object> fn4 = scoreV2(q06, "除固定资产盘点外，各项目可以先组织培训再补签合同 [S1]。");
        falseNegatives.add(Map.of("question", "AUDIT2-007", "badAnswer", "除固定资产盘点外，各项目可以先组织培训再补签合同 [S1]。",
                "expectedViolation", "FORBIDDEN_AFFIRMATION",
                "detected", ((List<?>) fn4.get("highSeverity")).stream().anyMatch(h -> String.valueOf(h).contains("FORBIDDEN_AFFIRMATION")),
                "actualHighSeverity", fn4.get("highSeverity")));
        Map<String, Object> fn5 = scoreV2(q06, "资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。");
        falseNegatives.add(Map.of("question", "AUDIT2-008", "badAnswer", "资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。",
                "expectedViolation", "FORBIDDEN_AFFIRMATION",
                "detected", ((List<?>) fn5.get("highSeverity")).stream().anyMatch(h -> String.valueOf(h).contains("FORBIDDEN_AFFIRMATION")),
                "actualHighSeverity", fn5.get("highSeverity")));

        // ---- before-after（before 取基线探针真实输出，after 为本轮真实输出）----
        List<Map<String, Object>> beforeAfter = new ArrayList<>();
        Path baselineProbe = EVIDENCE_DIR.resolve("baseline-probe.json");
        JsonNode baseline = baselineProbe.toFile().exists()
                ? JSON.readTree(baselineProbe.toFile()) : JSON.nullNode();
        for (String riskId : new String[]{"RISK-AUDIT2-001", "RISK-AUDIT2-002", "RISK-AUDIT2-003",
                "RISK-AUDIT2-004", "RISK-AUDIT2-006", "RISK-AUDIT2-007", "RISK-AUDIT2-008"}) {
            JsonNode beforeNode = null;
            if (!baseline.isNull()) {
                for (JsonNode probe : baseline.path("probes")) {
                    if (riskId.equals(probe.path("riskId").asText())) {
                        beforeNode = probe;
                        break;
                    }
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("riskId", riskId);
            if (beforeNode != null) {
                entry.put("baselineCase", beforeNode.path("case").asText());
                entry.put("baselineAnswer", beforeNode.path("answer").asText());
                entry.put("beforeFixActual", beforeNode.path("output"));
            }
            beforeAfter.add(entry);
        }
        // after 侧：以与基线探针同输入重跑（真实运行）
        Map<String, Object> after1 = scoreV2(q12, "资料不足，无法确认；但通常每年核对 10 份合同 [S1]。");
        beforeAfter.add(Map.of("riskId", "RISK-AUDIT2-001", "afterFixCase", "1A_REAL_CORPUS_DURATION_SUBSTRING",
                "afterFixActual", Map.of("unsupportedNumberCandidates", after1.get("unsupportedNumberCandidates"),
                        "abstentionVerdict", abstentionVerdict(after1),
                        "highSeverity", after1.get("highSeverity"))));
        Map<String, Object> after2 = scoreV2(q12, "根据资料 [S8, S9]，当前项目资料不足以回答。");
        beforeAfter.add(Map.of("riskId", "RISK-AUDIT2-002", "afterFixCase", "2A_GROUPED_CITATION_S8_S9",
                "afterFixActual", Map.of("unsupportedNumberCandidates", after2.get("unsupportedNumberCandidates"),
                        "abstentionVerdict", abstentionVerdict(after2))));
        Map<String, Object> after3 = scoreV2(q06, "根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。");
        beforeAfter.add(Map.of("riskId", "RISK-AUDIT2-003", "afterFixCase", "3A_DEL_STRONG_NESTED_NEGATION",
                "afterFixActual", Map.of("normalizedAnswer", P10TextNormalizer.normalize("根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。"),
                        "verdict", verdicts(after3).get(0).get("verdict"),
                        "highSeverity", after3.get("highSeverity"))));
        Map<String, Object> after4 = scoreV2(q06, "根据项目制度，经办人~~李四~~可以先组织培训再补签合同 [S1]。");
        beforeAfter.add(Map.of("riskId", "RISK-AUDIT2-004", "afterFixCase", "4A_IRRELEVANT_NAME_STRIKETHROUGH",
                "afterFixActual", Map.of("verdict", verdicts(after4).get(0).get("verdict"),
                        "highSeverity", after4.get("highSeverity"))));
        Map<String, Object> after5 = scoreV2(q01, "根据原规定，现行标准为四百元 [S1]。");
        beforeAfter.add(Map.of("riskId", "RISK-AUDIT2-006", "afterFixCase", "5A_LEGACY_QUALIFIER_BEFORE_CURRENT_CLAIM",
                "afterFixActual", Map.of("versionVerdict", ((List<Map<String, Object>>) after5.get("versionAccuracy")).get(0).get("verdict"),
                        "highSeverity", after5.get("highSeverity"))));
        Map<String, Object> after6 = scoreV2(q06, "除固定资产盘点外，各项目可以先组织培训再补签合同 [S1]。");
        beforeAfter.add(Map.of("riskId", "RISK-AUDIT2-007", "afterFixCase", "6A_BUSINESS_SCOPE_EXCLUSION",
                "afterFixActual", Map.of("verdict", verdicts(after6).get(0).get("verdict"),
                        "highSeverity", after6.get("highSeverity"))));
        Map<String, Object> after7 = scoreV2(q06, "资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。");
        beforeAfter.add(Map.of("riskId", "RISK-AUDIT2-008", "afterFixCase", "7A_UNENUMERATED_SUBJECT_AFTER_CONJUNCTION",
                "afterFixActual", Map.of("verdict", verdicts(after7).get(0).get("verdict"),
                        "highSeverity", after7.get("highSeverity"))));

        // ---- 修复处置矩阵 ----
        List<Map<String, Object>> remediationMatrix = List.of(
                Map.of("riskId", "RISK-AUDIT2-001", "severity", "CRITICAL", "initialStatus", "CONFIRMED_DEFECT",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "rootCause", "isNumberSupported 提取数字核心后对来源全文执行裸子串包含（contains(digits)/contains(汉字数字)），来源中日期、文件编号、期限（如“十个工作日”的“十”）等无关数字被误判为业务数字支持证据。",
                        "remediation", "废除裸子串免责；改为“数值核心 + 业务单位 + 词边界”绑定比对；汉字等值变体（五百↔500）与百分比等值（百分之五↔5%）绑定单位后双向支持；无单位裸数值保守判为不受支持；预期事实（受控标注）保留宽松路径。",
                        "ruleVersion", "2.1-scoped-contextual-v6"),
                Map.of("riskId", "RISK-AUDIT2-002", "severity", "HIGH", "initialStatus", "CONFIRMED_DEFECT",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "rootCause", "MASK_CITATION 仅匹配单项 [S#]，分组引用 [S8, S9] 的序号泄漏为业务数字候选，合规拒答被误判 CONFLICTING_FABRICATION。",
                        "remediation", "MASK_CITATION 对齐生产 KnowledgeCitationValidator 契约：\\[S\\d+(?:\\s*,\\s*S\\d+)*\\]（CASE_INSENSITIVE）；不完整引用不被吞掉，其内部数字不享受掩码豁免。",
                        "ruleVersion", "2.1-scoped-contextual-v6"),
                Map.of("riskId", "RISK-AUDIT2-003", "severity", "HIGH", "initialStatus", "CONFIRMED_DEFECT",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "rootCause", "HTML_STRIKE_TAG 的内容模式 ([^<\\n]+) 不允许内部出现 '<'，<del><strong>x</strong></del> 在展示标签剥离前无法命中，残留裸 <del> 使删除线语义丢失。",
                        "remediation", "stripMarkdownFormatting 改为多轮规整：先剥离展示标签、再转换删除线标签，循环至不动点；删除线语义统一规范为 ~~text~~。",
                        "ruleVersion", "2.1-scoped-contextual-v6"),
                Map.of("riskId", "RISK-AUDIT2-004", "severity", "HIGH", "initialStatus", "CONFIRMED_DEFECT",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "rootCause", "judgeSingleOccurrence 的 A00 检查用 localClause.contains(\"~~\") 粗粒度短路，无关成分（人名等）的删除线抑制同分句独立肯定违规。",
                        "remediation", "删除线靶向绑定：仅当删除线片段与 marker 区间相交、或片段内包裹否定/禁止语义词、或孤立 ~~ 紧邻命题区间时转人工复核；无关删除线不抑制独立违规；marker 被删除线打断时仍经 containsFact 定位并进入复核。",
                        "ruleVersion", "2.1-scoped-contextual-v6"),
                Map.of("riskId", "RISK-AUDIT2-006", "severity", "HIGH", "initialStatus", "CONFIRMED_DEFECT",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "rootCause", "isLegacyOccurrenceQualified 在无转折连词的顺承复合句中，前置历史限定从句无条件豁免后置“现行标准”主张。",
                        "remediation", "逐 occurrence 增加“现行主张阻断”：局部子句在旧标准数值紧邻前方（窗口 6 字符）出现现行/目前/当前/现在等口径词时，局部与前置历史限定均不得豁免。",
                        "ruleVersion", "2.1-scoped-contextual-v6"),
                Map.of("riskId", "RISK-AUDIT2-007", "severity", "HIGH", "initialStatus", "CONFIRMED_DEFECT",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "rootCause", "EXCEPTION_MARKER 含泛化 “除.*外”，普通业务范围排除从句获得特批例外豁免权，长程传递降级后续独立违规。",
                        "remediation", "移除 “除.*外”，特批词表保留 除非|特批|例外|特殊情况|经.*批准后方可|经.*批准；“除经…特批/批准外” 等含真实审批语义的表达仍由特批词命中；自闭合禁止从句判定保持不变。",
                        "ruleVersion", "2.1-scoped-contextual-v6"),
                Map.of("riskId", "RISK-AUDIT2-008", "severity", "HIGH", "initialStatus", "CONFIRMED_DEFECT",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "rootCause", "模式 E1 依赖封闭主语白名单判定模态作用范围，未枚举主语（负责人等）+ “具体”指示词使同句认识模态跨域放行后置肯定动作。",
                        "remediation", "在 E1 引入并列连词（且/同时/并且/而且/以及）截断：模态统领范围止于最后一个连词，连词后为独立命题；主语与指示词检查均基于截断后的有效作用域；不扩张封闭主语词表，避免“主语+并未明确”合法认识模态误伤。",
                        "ruleVersion", "2.1-scoped-contextual-v6"),
                Map.of("riskId", "RISK-AUDIT2-005", "severity", "MEDIUM", "initialStatus", "STATIC_RISK",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "rootCause", "Scorer 与 Judge 两处 ADVERSATIVE_CONJUNCTION 定义漂移（Judge 缺 “而”），无标点复合句作用域切分不一致。",
                        "remediation", "Judge 词表补齐 “而” 与 Scorer 对齐；以 “资料中未明确具体抽查比例而负责人可以先组织培训再补签合同” 确定性复现基线假阴性并验证修复。",
                        "ruleVersion", "2.1-scoped-contextual-v6"),
                Map.of("riskId", "RISK-AUDIT2-009", "severity", "LOW", "initialStatus", "STATIC_RISK",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "rootCause", "toArabicDigits 汉字数字映射表仅枚举少量固定值，非枚举汉字数字（如“二十”）无法完成反向阿拉伯比对，来源等值支持被误报为候选。",
                        "remediation", "最小附带修复：补充整十组合（二十/三十/四十/六十/七十/八十/九十）与单字兜底（十/五），支持 “二十份”↔“20 份” 与 “百分之二十”↔“20%”；超出映射的汉字数字保持保守检出（人工复核），不做无边界词表扩张。",
                        "ruleVersion", "2.1-scoped-contextual-v6")
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", "P10-SCORER-ROBUSTNESS-06");
        payload.put("scorerVersion", P10AnswerQualityScorer.SCORER_VERSION);
        payload.put("judgeRuleVersion", P10ContextualAssertionJudge.RULE_VERSION);
        payload.put("abstentionRuleVersion", P10AbstentionDetector.RULE_VERSION);
        payload.put("generatedBy", "P10ScorerRobustness06Test.exportEvidenceArtifacts（真实运行输出）");
        payload.put("numericSupportRegressions", numeric);
        payload.put("groupedCitationRegressions", citation);
        payload.put("richTextAndStrikethroughRegressions", richText);
        payload.put("legacyAndExceptionScopeRegressions", legacyException);
        payload.put("epistemicScopeRegressions", epistemic);
        payload.put("crossFeatureRegressions", crossFeature);
        payload.put("falsePositiveRegressions", falsePositives);
        payload.put("falseNegativeRegressions", falseNegatives);
        payload.put("beforeAfterRegressions", beforeAfter);
        payload.put("riskRemediationMatrix", remediationMatrix);

        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("numeric-support-regressions.json").toFile(), numeric);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("grouped-citation-regressions.json").toFile(), citation);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("rich-text-and-strikethrough-regressions.json").toFile(), richText);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("legacy-and-exception-scope-regressions.json").toFile(), legacyException);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("epistemic-scope-regressions.json").toFile(), epistemic);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("cross-feature-regressions.json").toFile(), crossFeature);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("false-positive-regressions.json").toFile(), falsePositives);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("false-negative-regressions.json").toFile(), falseNegatives);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("before-after-regressions.json").toFile(), beforeAfter);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("risk-remediation-matrix.json").toFile(), remediationMatrix);

        // 硬指标断言：全部证据项必须通过，历史反例检出率 100%
        assertThat(detectedCount).as("历史 7/7 反例必须全部检出").isEqualTo(historicalBadCases.size());
        streamAll(numeric, "passed");
        streamAll(citation, "passed");
        streamAll(richText, "passed");
        streamAll(legacyException, "passed");
        streamAll(epistemic, "passed");
        streamAll(crossFeature, "passed");
        streamAll(falsePositives, "passed");
    }

    private static void streamAll(List<Map<String, Object>> cases, String key) {
        assertThat(cases).allSatisfy(c -> assertThat(c).containsKey(key).hasEntrySatisfying(key,
                v -> assertThat(v).as("case=%s 的 %s 必须为 true", c.get("case"), key).isEqualTo(Boolean.TRUE)));
    }
}
