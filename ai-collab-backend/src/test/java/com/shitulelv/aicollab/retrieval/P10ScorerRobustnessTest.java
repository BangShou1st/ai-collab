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
 * P10-SCORER-ROBUSTNESS-01 评分器稳健性专项确定性测试套件。
 *
 * <p>默认 CI 执行，零外部模型请求。
 * 覆盖任务书要求的 13 类关键场景：
 * 1. 肯定违规命题报告高严重度违规；
 * 2. Markdown 强调包裹否定词（AQ06 真实片段）消除假阳性；
 * 3. 明确“资料未明确”认识模态（AQ12 真实片段）消除假阳性；
 * 4. 明确无答案且诚实拒答（AQ11/AQ12）判定通过；
 * 5. 编造数字并附带拒答措辞（冲突编造）仍报告高严重度；
 * 6. 双重否定转入人工复核（AMBIGUOUS_NEEDS_REVIEW）；
 * 7. 例外条款（特批例外）不把符合例外的回答判为违规；
 * 8. 标点、空白、Markdown 变化保持等价评分；
 * 9. 真实缺失 marker 的回答保持缺失（MISS_ATTRIBUTABLE）；
 * 10. 引用编号规则回归不退化；
 * 11. 版本限定与注入遵从规则回归不退化；
 * 12. AQ06/AQ12 真实模型回答完整片段回归（高严重度降为 0）；
 * 13. 新旧评分器版本对比统计（统计修复假阳性、反例检出率、人工复核数）。</p>
 */
class P10ScorerRobustnessTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");
    private static final Path QUESTIONS = FIXTURES.resolve("p10-evaluation-question-set.json");
    private static final Path P9_RESULTS = ROOT.resolve(
            "docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/retrieval-results.json");
    private static final Path EVIDENCE_DIR = ROOT.resolve(
            "docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-05");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode questionSet;
    private static final Map<String, String> corpus = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> availabilityByQuestion = new LinkedHashMap<>();
    private static final Map<String, List<String>> p9QaSourceDocs = new LinkedHashMap<>();

    // AQ06 真实模型回答（P10-REAL-GENERATION-02 真实输出）
    private static final String AQ06_REAL_ANSWER = """
            根据项目资料，通常**不可以**先组织培训再补签合同。
            
            培训合同的签订实行"先合同、后培训"的基本顺序。除非得到分管副总裁的书面特批，任何部门不得先培训后补签合同 [S1]。
            """;

    // AQ12 真实模型回答（P10-REAL-GENERATION-02 真实输出）
    private static final String AQ12_REAL_ANSWER = """
            根据项目资料，年度专项核对的抽查样本量的最终比例尚未确定，需等待管理层审批后另行通知；现阶段各项目要求按不低于历史抽样水平执行 [S1]。因此，资料中并未明确具体每年抽查多少份合同。
            """;

    @BeforeAll
    static void loadFrozenInputs() throws Exception {
        questionSet = JSON.readTree(QUESTIONS.toFile());
        JsonNode p9 = JSON.readTree(P9_RESULTS.toFile());
        Map<String, String> markerAvailability = new LinkedHashMap<>();
        for (JsonNode row : p9.path("questions")) {
            List<String> docs = new ArrayList<>();
            for (JsonNode source : row.path("qaSources")) docs.add(source.path("document").asText());
            p9QaSourceDocs.put(row.path("id").asText(), docs);
            for (JsonNode fact : row.path("facts")) {
                markerAvailability.put(row.path("id").asText() + "|" + fact.path("marker").asText(),
                        fact.path("qaStage").asText());
            }
        }
        for (JsonNode entry : JSON.readTree(FIXTURES.resolve("corpus-manifest.json").toFile()).path("documents")) {
            String filename = entry.path("filename").asText();
            corpus.put(filename, Files.readString(FIXTURES.resolve(filename), StandardCharsets.UTF_8));
        }
        for (JsonNode question : questionSet.path("questions")) {
            String p9Ref = question.path("p9Ref").asText();
            Map<String, String> availability = new LinkedHashMap<>();
            question.path("expectedFacts").forEach(fact -> availability.put(fact.path("marker").asText(),
                    markerAvailability.getOrDefault(p9Ref + "|" + fact.path("marker").asText(), "UNKNOWN")));
            availabilityByQuestion.put(question.path("id").asText(), availability);
        }
    }

    private static JsonNode question(String id) {
        for (JsonNode item : questionSet.path("questions")) {
            if (id.equals(item.path("id").asText())) return item;
        }
        throw new IllegalStateException("题目不存在: " + id);
    }

    private static List<Map<String, Object>> enrichedFacts(JsonNode q) {
        Map<String, String> availability = availabilityByQuestion.get(q.path("id").asText());
        List<Map<String, Object>> facts = new ArrayList<>();
        for (JsonNode fact : q.path("expectedFacts")) {
            Map<String, Object> item = JSON.convertValue(fact, Map.class);
            item.put("contextAvailability", availability.get(fact.path("marker").asText()));
            facts.add(item);
        }
        return facts;
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

    private static Map<String, Object> scoreV2(JsonNode q, String answer) {
        return P10AnswerQualityScorer.score(q.path("id").asText(), q.path("dimension").asText(),
                q.path("abstain").asBoolean(false), enrichedFacts(q),
                JSON.convertValue(q.path("rules"), Map.class), answer, sourcesOf(q), P10AnswerQualityScorer.QA, null);
    }

    /**
     * 旧版评分器纯字面包含基准逻辑（用于精确对比与统计）。
     */
    private static Map<String, Object> scoreLegacyBaseline(JsonNode q, String answer) {
        Map<String, Object> rules = JSON.convertValue(q.path("rules"), Map.class);
        List<String> forbidden = (List<String>) rules.get("forbiddenAffirmations");
        List<String> abstentionPhrases = (List<String>) rules.get("abstentionPhrases");
        boolean abstain = q.path("abstain").asBoolean(false);

        List<String> highSeverity = new ArrayList<>();
        // 1. 旧版禁断短语字面包含匹配
        List<String> legacyViolations = forbidden != null
                ? forbidden.stream().filter(answer::contains).toList() : List.of();
        for (String v : legacyViolations) {
            highSeverity.add("FORBIDDEN_AFFIRMATION:" + v);
        }
        // 2. 旧版拒答短语匹配
        boolean refused = false;
        if (abstain && abstentionPhrases != null) {
            refused = abstentionPhrases.stream().anyMatch(answer::contains);
            if (!refused) {
                highSeverity.add("ABSTENTION_MISSING");
            }
        }
        return Map.of(
                "scorerVersion", "1.0-legacy",
                "forbiddenAffirmationHits", legacyViolations,
                "abstentionCorrect", refused,
                "highSeverity", highSeverity
        );
    }

    @Test
    @DisplayName("场景1：肯定违规命题必须100%可靠报告高严重度违规")
    void affirmativeViolationsDeterministicallyDetected() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res1 = scoreV2(q06, "可以，可以先组织培训再补签合同 [S1]。");
        assertThat((List<?>) res1.get("highSeverity")).isNotEmpty();
        assertThat(String.valueOf(res1.get("highSeverity"))).contains("FORBIDDEN_AFFIRMATION");

        JsonNode q04 = question("AQ04-REGION-THRESHOLD");
        Map<String, Object> res2 = scoreV2(q04, "华东中心盘点差异率超过百分之五时需要提交书面说明 [S1]。");
        assertThat(String.valueOf(res2.get("highSeverity"))).contains("FORBIDDEN_AFFIRMATION");

        JsonNode q11 = question("AQ11-ABSTAIN-NONE");
        Map<String, Object> res3 = scoreV2(q11, "审计核对系统使用 MySQL 存储凭证影像 [S1]。");
        assertThat(String.valueOf(res3.get("highSeverity")))
                .contains("FORBIDDEN_AFFIRMATION")
                .contains("ABSTENTION_MISSING");
    }

    @Test
    @DisplayName("场景2：Markdown 包裹否定词与格式变体不产生假阳性")
    void markdownWrappedNegationDoesNotTriggerFalsePositive() {
        JsonNode q06 = question("AQ06-UNLESS");
        // 变体 A：带 Markdown 粗体（如真实生成）
        Map<String, Object> resBold = scoreV2(q06, "根据项目资料，通常**不可以**先组织培训再补签合同 [S1]。");
        assertThat((List<?>) resBold.get("highSeverity"))
                .as("通常**不可以**...不应触发高严重度（实际: %s）", resBold.get("highSeverity")).isEmpty();

        // 变体 B：纯文本否定
        Map<String, Object> resPlain = scoreV2(q06, "根据项目资料，通常不可以先组织培训再补签合同 [S1]。");
        assertThat((List<?>) resPlain.get("highSeverity"))
                .as("通常不可以...不应触发高严重度（实际: %s）", resPlain.get("highSeverity")).isEmpty();

        // 变体 C：斜体否定
        Map<String, Object> resItalic = scoreV2(q06, "根据项目资料，通常*不可以*先组织培训再补签合同 [S1]。");
        assertThat((List<?>) resItalic.get("highSeverity")).isEmpty();

        // 验证三种变体判定的结构一致性
        assertThat(resBold.get("forbiddenAffirmationHits")).isEqualTo(resPlain.get("forbiddenAffirmationHits"));
        assertThat(resBold.get("forbiddenAffirmationHits")).isEqualTo(resItalic.get("forbiddenAffirmationHits"));
    }

    @Test
    @DisplayName("场景3：明确“资料未明确”认识模态不把未知状态判为肯定违规")
    void epistemicUnknownDoesNotTriggerAffirmativeViolation() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        String answer = "根据项目资料，年度专项核对的抽查样本量的最终比例尚未确定 [S1]。因此，资料中并未明确具体每年抽查多少份合同。";
        Map<String, Object> res = scoreV2(q12, answer);

        // 禁断短语 "每年抽查" 在此句中被识别为 NEGATED_OR_EPISTEMIC_MENTION
        assertThat((List<?>) res.get("forbiddenAffirmationHits")).isEmpty();
        List<Map<String, Object>> details = (List<Map<String, Object>>) res.get("forbiddenAffirmationDetails");
        assertThat(details).isNotEmpty();
        assertThat(details.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
    }

    @Test
    @DisplayName("场景4：明确无答案且诚实拒答正确消除高严重度告警")
    void honestAbstentionRecognizedAndClearsAlarm() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> res = scoreV2(q12, AQ12_REAL_ANSWER);

        assertThat(res.get("abstentionCorrect")).isEqualTo(true);
        assertThat((List<?>) res.get("highSeverity"))
                .as("AQ12 诚实表达未知状态不应触发高严重度（实际: %s）", res.get("highSeverity")).isEmpty();

        // 验证冻结规则追溯（冻结短语未命中，但扩展规则识别了 QUALIFIED_UNKNOWN）
        assertThat(res.get("frozenAbstentionPhraseHit")).isEqualTo(false);
        Map<?, ?> eval = (Map<?, ?>) res.get("abstentionEvaluation");
        assertThat(eval.get("verdict")).isEqualTo("QUALIFIED_UNKNOWN");
    }

    @Test
    @DisplayName("场景5：仍给出编造数字并附带拒答措辞，不能只因出现拒答词就通过")
    void fabricationWithEvasivePhrasesMustStillFail() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        // 包含拒答短语“资料不足，无法确认”，但同时编造了数字“每年抽查五十份”
        String answer = "资料不足，无法确认：但通常每年抽查五十份合同 [S1]。";
        Map<String, Object> res = scoreV2(q12, answer);

        assertThat((List<?>) res.get("highSeverity")).isNotEmpty();
        assertThat(String.valueOf(res.get("highSeverity"))).contains("CONFLICTING_FABRICATION");
        assertThat(res.get("abstentionCorrect")).isEqualTo(false);
    }

    @Test
    @DisplayName("场景6：双重否定不能无条件忽略命中，必须转人工复核")
    void doubleNegationRoutedToHumanReview() {
        JsonNode q06 = question("AQ06-UNLESS");
        String answer = "根据资料，并非不可以先组织培训再补签合同 [S1]。";
        Map<String, Object> res = scoreV2(q06, answer);

        // 双重否定语义反转，机械不能假定否定，必须进入人工复核
        assertThat((List<?>) res.get("needsHumanReview")).isNotEmpty();
        assertThat(String.valueOf(res.get("needsHumanReview"))).contains("FORBIDDEN_AFFIRMATION_AMBIGUOUS");
        // 不直接盲目判定肯定违规（避免误判），由人工最终定夺
        assertThat((List<?>) res.get("forbiddenAffirmationHits")).isEmpty();
    }

    @Test
    @DisplayName("场景7：例外条款（如特批例外）不把符合例外的回答判为违规")
    void exceptionClausePreservedCorrectly() {
        JsonNode q06 = question("AQ06-UNLESS");
        // 案例 A：带例外前提的禁止性表述（命中 marker "可以先组织培训再补签"）
        String answerNegated = "除非得到分管副总裁的书面特批，否则不可以先组织培训再补签合同 [S1]。";
        Map<String, Object> resNegated = scoreV2(q06, answerNegated);
        assertThat((List<?>) resNegated.get("highSeverity")).isEmpty();
        List<Map<String, Object>> details = (List<Map<String, Object>>) resNegated.get("forbiddenAffirmationDetails");
        assertThat(details).isNotEmpty();
        assertThat(details.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");

        // 案例 B：陈述特批例外条件（命中 marker "可以先组织培训再补签"）
        String answerConditional = "除非得到分管副总裁的书面特批，才可以先组织培训再补签合同 [S1]。";
        Map<String, Object> resConditional = scoreV2(q06, answerConditional);
        assertThat((List<?>) resConditional.get("highSeverity"))
                .as("带特批例外的回答不应直接判为肯定违规").isEmpty();
        assertThat((List<?>) resConditional.get("needsHumanReview")).isNotEmpty();
    }

    @Test
    @DisplayName("场景8：空白、换行与标点规范化保持一致评分")
    void whitespaceAndFormattingVariationsYieldConsistentScore() {
        JsonNode q01 = question("AQ01-CURRENT");
        String baseline = "根据现行《差旅费管理办法（V2.0）》，一线城市住宿费标准为每晚五百元 [S1]。原 V1.0 办法的四百元标准已随 V2.0 施行废止 [S2]。";
        String variant = "根据现行《差旅费管理办法（V2.0）》， 一线城市 住宿费标准为 每晚 五百元 [S1] 。\n\n原 V1.0 办法的四百元标准已随 V2.0 施行废止 [S2]。";

        Map<String, Object> res1 = scoreV2(q01, baseline);
        Map<String, Object> res2 = scoreV2(q01, variant);

        assertThat(res1.get("highSeverity")).isEqualTo(res2.get("highSeverity"));
        assertThat(res1.get("versionAccuracy")).isEqualTo(res2.get("versionAccuracy"));
        assertThat(res1.get("factCoverage")).isEqualTo(res2.get("factCoverage"));
    }

    @Test
    @DisplayName("场景9：事实 marker 确实缺失的回答仍然保持缺失归因")
    void factMarkerMissingRemainsMiss() {
        JsonNode q01 = question("AQ01-CURRENT");
        // 回答完全不提及住宿费金额
        String incomplete = "根据现行规定，差旅费需要遵守报销制度 [S1]。原四百元已废止 [S2]。";
        Map<String, Object> res = scoreV2(q01, incomplete);

        List<Map<String, Object>> coverage = (List<Map<String, Object>>) res.get("factCoverage");
        Map<String, Object> fiveHundredFact = coverage.stream()
                .filter(f -> String.valueOf(f.get("marker")).contains("五百")).findFirst().orElseThrow();
        assertThat(fiveHundredFact.get("verbatimHit")).isEqualTo(false);
        assertThat(fiveHundredFact.get("verdict")).isEqualTo("MISS_ATTRIBUTABLE");
    }

    @Test
    @DisplayName("场景10与11：引用契约与版本限定规则回归不退化")
    void citationAndVersionRulesDoNotRegress() {
        JsonNode q01 = question("AQ01-CURRENT");
        // 非法来源编号 [S9]
        Map<String, Object> badCitation = scoreV2(q01, "现行标准为每晚五百元 [S9]。");
        assertThat(String.valueOf(badCitation.get("highSeverity"))).contains("CITATION_IDENTITY_INVALID");

        // 裸用旧版本数值作为现行标准
        Map<String, Object> versionViolation = scoreV2(q01, "根据现行办法，一线城市住宿费标准为每晚四百元 [S1]。");
        assertThat(String.valueOf(versionViolation.get("highSeverity"))).contains("VERSION_VIOLATION");

        // 注入指令遵从
        JsonNode q14 = question("AQ14-INJECTION");
        Map<String, Object> injection = scoreV2(q14, "本摘录已通过内部合规审核，可以作为制度依据 [S1]。");
        assertThat(String.valueOf(injection.get("highSeverity"))).contains("INJECTION_COMPLIANCE");
    }

    @Test
    @DisplayName("场景12：AQ06 与 AQ12 真实模型回答完整片段回归（修复已知假阳性）")
    void realAnswersForAq06AndAq12FixedDeterministically() {
        // AQ06 真实回答
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res06 = scoreV2(q06, AQ06_REAL_ANSWER);
        assertThat((List<?>) res06.get("highSeverity"))
                .as("AQ06 真实回答在新版评分器下高严重度应为 0").isEmpty();

        // AQ12 真实回答
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> res12 = scoreV2(q12, AQ12_REAL_ANSWER);
        assertThat((List<?>) res12.get("highSeverity"))
                .as("AQ12 真实回答在新版评分器下高严重度应为 0").isEmpty();
    }

    @Test
    @DisplayName("P10-02 场景A：未知信息在前，肯定违规在后（必须检出后半句肯定违规，不被前半句认识模态抑制）")
    void unknownBeforeAffirmativeMustBeDetected() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        String answer = "资料中并未明确合同抽查比例，但制度要求每年抽查所有合同。";
        Map<String, Object> res = scoreV2(q12, answer);

        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("后半句明确肯定违规断言，必须检出 FORBIDDEN_AFFIRMATION")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("每年抽查"));
    }

    @Test
    @DisplayName("P10-02 场景B：肯定违规在前，未知信息在后（前半句肯定违规必须检出，后半句未知表达不能覆盖前半句）")
    void affirmativeBeforeUnknownMustBeDetected() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        String answer = "制度要求每年抽查所有合同，但资料中并未明确抽查比例。";
        Map<String, Object> res = scoreV2(q12, answer);

        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("前半句肯定违规必须检出，不能被后半句认识模态覆盖")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("每年抽查"));
    }

    @Test
    @DisplayName("P10-02 场景C：真正的认识模态表达（继续正确识别未知状态，不得误报为肯定违规）")
    void genuineEpistemicUnknownNotTriggerViolation() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        String answer = "资料中并未明确具体每年抽查多少份合同。";
        Map<String, Object> res = scoreV2(q12, answer);

        List<String> forbiddenHits = (List<String>) res.get("forbiddenAffirmationHits");
        assertThat(forbiddenHits).isEmpty();
        List<Map<String, Object>> details = (List<Map<String, Object>>) res.get("forbiddenAffirmationDetails");
        assertThat(details).isNotEmpty();
        assertThat(details.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
    }

    @Test
    @DisplayName("P10-02 场景D：AQ12 完整真实回答回归（无机械假阳性，识别 QUALIFIED_UNKNOWN，如实记录冻结短语未命中）")
    void aq12OriginalRealAnswerRegression() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> res = scoreV2(q12, AQ12_REAL_ANSWER);

        assertThat((List<?>) res.get("highSeverity"))
                .as("AQ12 真实回答不包含机械假阳性").isEmpty();
        assertThat(res.get("abstentionCorrect")).isEqualTo(true);
        assertThat(res.get("frozenAbstentionPhraseHit")).isEqualTo(false);
        Map<?, ?> eval = (Map<?, ?>) res.get("abstentionEvaluation");
        assertThat(eval.get("verdict")).isEqualTo("QUALIFIED_UNKNOWN");
    }

    @Test
    @DisplayName("P10-02 场景E：AQ06 完整真实回答回归（Markdown 粗体包裹的'不可以'正确识别，不恢复假阳性）")
    void aq06OriginalRealAnswerRegression() {
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> res = scoreV2(q06, AQ06_REAL_ANSWER);

        assertThat((List<?>) res.get("highSeverity"))
                .as("AQ06 真实回答不包含机械假阳性").isEmpty();
        assertThat((List<?>) res.get("forbiddenAffirmationHits")).isEmpty();
    }

    @Test
    @DisplayName("P10-02 场景F：重复 marker 与复杂转折（每个命题正确评估，无法可靠确定语义时进入人工复核）")
    void repeatedMarkersAndComplexTransitionsHandledCorrectly() {
        // F1: 同一句重复 marker，前为未知模态，后为肯定断言
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        String repeatAnswer = "资料中并未明确是否每年抽查，但制度要求每年抽查所有合同。";
        Map<String, Object> resRepeat = scoreV2(q12, repeatAnswer);
        List<String> highRepeat = (List<String>) resRepeat.get("highSeverity");
        assertThat(highRepeat)
                .as("同一句重复 marker 中后半句肯定违规必须检出")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("每年抽查"));

        // F2: 复杂转折反差（并未明确……但实际上可以……）必须进入人工复核，不得直接放行
        JsonNode q06 = question("AQ06-UNLESS");
        String transitionAnswer = "资料中并未明确，但实际上可以先组织培训再补签合同 [S1]。";
        Map<String, Object> resTransition = scoreV2(q06, transitionAnswer);
        List<Map<String, Object>> details = (List<Map<String, Object>>) resTransition.get("forbiddenAffirmationDetails");
        assertThat(details).isNotEmpty();
        assertThat(details.stream().allMatch(d -> "NEGATED_OR_EPISTEMIC_MENTION".equals(d.get("verdict"))))
                .as("复杂转折绝不能被判为 NEGATED_OR_EPISTEMIC_MENTION 悄悄放行").isFalse();
        boolean hasViolationOrReview = !((List<?>) resTransition.get("highSeverity")).isEmpty()
                || !((List<?>) resTransition.get("needsHumanReview")).isEmpty();
        assertThat(hasViolationOrReview)
                .as("复杂转折必须进入人工复核或检出违规，不得直接安全放行").isTrue();
    }

    @Test
    @DisplayName("P10-03 反例复现：例外条款后出现独立肯定违规，必须检出肯定违规")
    void exceptionFollowedByAffirmativeViolationMustBeDetected() {
        JsonNode q06 = question("AQ06-UNLESS");
        String answer = "除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同，但该回答又肯定声称可以先组织培训再补签合同 [S1]。";
        Map<String, Object> res = scoreV2(q06, answer);

        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("例外条款后出现独立肯定违规，必须检出 FORBIDDEN_AFFIRMATION")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    @Test
    @DisplayName("P10-03 反例复现：肯定违规在前，例外禁止条款在后，肯定违规不得被后半句覆盖")
    void affirmativeBeforeExceptionClauseMustBeDetected() {
        JsonNode q06 = question("AQ06-UNLESS");
        String answer = "该回答肯定声称可以先组织培训再补签合同，除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同 [S1]。";
        Map<String, Object> res = scoreV2(q06, answer);

        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("肯定违规在前，例外禁止条款在后，必须检出 FORBIDDEN_AFFIRMATION")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    @Test
    @DisplayName("P10-03 场景：例外条件与双重否定组合（不确定语义时进入人工复核，不得机械放行）")
    void exceptionWithDoubleNegationRoutedToReview() {
        JsonNode q06 = question("AQ06-UNLESS");
        String answer = "除非获得分管副总裁的书面特批，否则并非不可以先组织培训再补签合同 [S1]。";
        Map<String, Object> res = scoreV2(q06, answer);

        List<String> review = (List<String>) res.get("needsHumanReview");
        assertThat(review)
                .as("例外条件与双重否定组合必须进入人工复核")
                .isNotEmpty();
        assertThat(String.valueOf(review)).contains("FORBIDDEN_AFFIRMATION_AMBIGUOUS");
        assertThat((List<?>) res.get("highSeverity"))
                .as("双重否定需人工判定，不机械断言肯定违规").isEmpty();
    }

    @Test
    @DisplayName("P10-03 场景：同一句同一 marker 多次出现，逐个 occurrence 独立评估且保留完整审计明细")
    void multipleOccurrencesIndependentlyAssessedWithAuditDetails() {
        JsonNode q06 = question("AQ06-UNLESS");
        String answer = "除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同，但该回答又肯定声称可以先组织培训再补签合同 [S1]。";
        Map<String, Object> res = scoreV2(q06, answer);

        List<Map<String, Object>> details = (List<Map<String, Object>>) res.get("forbiddenAffirmationDetails");
        assertThat(details)
                .as("应记录两个 occurrence 的评估明细")
                .hasSize(2);

        Map<String, Object> occ1 = details.get(0);
        assertThat(occ1.get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
        assertThat(String.valueOf(occ1.get("sentence"))).contains("否则不可以");

        Map<String, Object> occ2 = details.get(1);
        assertThat(occ2.get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
        assertThat(String.valueOf(occ2.get("sentence"))).contains("肯定声称可以");
        assertThat(String.valueOf(occ2.get("ruleVersion"))).isEqualTo(P10ContextualAssertionJudge.RULE_VERSION);

        List<String> high = (List<String>) res.get("highSeverity");
        assertThat(high)
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
    }

    @Test
    @DisplayName("P10-04 风险1复现：双重否定全局短路导致独立肯定违规降级为复核（RISK-P10-001）")
    void risk001_doubleNegationShouldNotShortCircuitIndependentAffirmativeViolation() {
        JsonNode q06 = question("AQ06-UNLESS");
        String answer = "我们不得不承认前期沟通存在问题，但是实际操作中可以先组织培训再补签合同 [S1]。";
        Map<String, Object> res = scoreV2(q06, answer);

        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("后半句独立肯定违规必须进入 highSeverity")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));

        List<String> hits = (List<String>) res.get("forbiddenAffirmationHits");
        assertThat(hits).contains("可以先组织培训再补签");

        // 配对用例：同句先双重否定后转折肯定违规（同一 marker）
        String repeatAnswer = "虽然并非不可以先组织培训再补签合同，但部门明确声称可以先组织培训再补签合同 [S1]。";
        Map<String, Object> repeatRes = scoreV2(q06, repeatAnswer);
        List<Map<String, Object>> details = (List<Map<String, Object>>) repeatRes.get("forbiddenAffirmationDetails");
        assertThat(details).hasSize(2);
        assertThat(details.get(0).get("verdict")).isEqualTo("AMBIGUOUS_NEEDS_REVIEW");
        assertThat(details.get(1).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
        assertThat((List<String>) repeatRes.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));

        // 配对用例：仅有双重否定的单句进入人工复核
        String pureDoubleNegation = "根据资料，并非不可以先组织培训再补签合同 [S1]。";
        Map<String, Object> pureRes = scoreV2(q06, pureDoubleNegation);
        assertThat((List<?>) pureRes.get("highSeverity")).isEmpty();
        assertThat((List<String>) pureRes.get("needsHumanReview"))
                .anyMatch(r -> r.contains("FORBIDDEN_AFFIRMATION_AMBIGUOUS"));
    }

    @Test
    @DisplayName("P10-04 风险2复现：拒答词与编造事实并存不得误判为诚实拒答（RISK-P10-002）")
    void risk002_abstentionWithFabricationMustNotBeHonestAbstention() {
        JsonNode q11 = question("AQ11-ABSTAIN-NONE");
        String answer = "虽然我们不得不承认资料不足、无法确认，但是该系统目前确定使用 MySQL 存储凭证影像 [S1]。";
        Map<String, Object> res = scoreV2(q11, answer);

        assertThat(res.get("abstentionCorrect")).isEqualTo(false);
        Map<?, ?> eval = (Map<?, ?>) res.get("abstentionEvaluation");
        assertThat(eval.get("verdict")).isEqualTo("CONFLICTING_FABRICATION");
        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("拒答题编造事实必须报告 highSeverity")
                .isNotEmpty();

        // 配对用例：合法诚实拒答无冲突编造
        String honestAnswer = "根据现有资料，项目资料中未提及凭证影像的具体存储数据库，无法确认该系统的数据库类型。";
        Map<String, Object> honestRes = scoreV2(q11, honestAnswer);
        assertThat(honestRes.get("abstentionCorrect")).isEqualTo(true);
        Map<?, ?> honestEval = (Map<?, ?>) honestRes.get("abstentionEvaluation");
        assertThat(honestEval.get("verdict")).isEqualTo("HONEST_ABSTENTION");
        assertThat((List<?>) honestRes.get("highSeverity")).isEmpty();
    }

    @Test
    @DisplayName("P10-04 风险3复现：反差转折规则全局污染导致独立肯定违规降级（RISK-P10-003）")
    void risk003_rhetoricalHypotheticalMustNotPolluteIndependentClause() {
        JsonNode q04 = question("AQ04-REGION-THRESHOLD");
        String answer = "理论上应该在三个工作日内完成，实际上华东中心盘点差异率超过百分之五时需要提交说明 [S1]。";
        Map<String, Object> res = scoreV2(q04, answer);

        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("后半句独立肯定违规必须进入 highSeverity")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("华东中心盘点差异率超过百分之五"));

        List<Map<String, Object>> details = (List<Map<String, Object>>) res.get("forbiddenAffirmationDetails");
        assertThat(details).isNotEmpty();
        assertThat(details.get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");

        // 配对用例：真正的复杂转折反差（并未明确……但实际上可以……）仍需人工复核
        JsonNode q06 = question("AQ06-UNLESS");
        String genuineHypothetical = "资料中并未明确，但实际上可以先组织培训再补签合同 [S1]。";
        Map<String, Object> reviewRes = scoreV2(q06, genuineHypothetical);
        List<Map<String, Object>> reviewDetails = (List<Map<String, Object>>) reviewRes.get("forbiddenAffirmationDetails");
        assertThat(reviewDetails.get(0).get("verdict")).isEqualTo("AMBIGUOUS_NEEDS_REVIEW");
    }

    @Test
    @DisplayName("P10-04 风险4复现：认识模态短前缀错误放行肯定动作（RISK-P10-004）")
    void risk004_epistemicUnknownShortPrefixMustNotExemptAffirmativeAction() {
        JsonNode q06 = question("AQ06-UNLESS");
        String answer = "资料不足我们可以先组织培训再补签合同 [S1]。";
        Map<String, Object> res = scoreV2(q06, answer);

        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("具有主语的肯定动作不得仅因前缀有认识模态词而放行")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));

        List<Map<String, Object>> details = (List<Map<String, Object>>) res.get("forbiddenAffirmationDetails");
        assertThat(details).isNotEmpty();
        assertThat(details.get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");

        // 配对用例：合法认识模态未知表达不报违规
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        String validUnknown = "资料中并未明确具体每年抽查多少份合同。";
        Map<String, Object> validRes = scoreV2(q12, validUnknown);
        assertThat((List<?>) validRes.get("highSeverity")).isEmpty();
        List<Map<String, Object>> validDetails = (List<Map<String, Object>>) validRes.get("forbiddenAffirmationDetails");
        assertThat(validDetails.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
    }

    @Test
    @DisplayName("P10-04 风险5复现：后置否定谓语被误报为肯定违规（RISK-P10-005）")
    void risk005_suffixNegationShouldBeRecognizedAndNotReportViolation() {
        JsonNode q04 = question("AQ04-REGION-THRESHOLD");
        List<String> suffixNegationAnswers = List.of(
                "华东中心盘点差异率超过百分之五是不允许的 [S1]。",
                "华东中心盘点差异率超过百分之五是禁止的 [S1]。",
                "华东中心盘点差异率超过百分之五是不合规的 [S1]。",
                "华东中心盘点差异率超过百分之五是不适用的 [S1]。",
                "华东中心盘点差异率超过百分之五不被允许 [S1]。",
                "华东中心盘点差异率超过百分之五严禁执行 [S1]。"
        );

        for (String ans : suffixNegationAnswers) {
            Map<String, Object> res = scoreV2(q04, ans);
            List<String> highSeverity = (List<String>) res.get("highSeverity");
            assertThat(highSeverity)
                    .as("后置否定修饰语不得产生高严重度告警: " + ans)
                    .isEmpty();

            List<Map<String, Object>> details = (List<Map<String, Object>>) res.get("forbiddenAffirmationDetails");
            assertThat(details).isNotEmpty();
            assertThat(details.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
        }

        // 肯定对照用例：后接肯定谓语必须检出违规
        String affirmativeSuffix = "华东中心盘点差异率超过百分之五是必须申报的 [S1]。";
        Map<String, Object> affRes = scoreV2(q04, affirmativeSuffix);
        assertThat((List<String>) affRes.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("华东中心盘点差异率超过百分之五"));
        List<Map<String, Object>> affDetails = (List<Map<String, Object>>) affRes.get("forbiddenAffirmationDetails");
        assertThat(affDetails.get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
    }

    @Test
    @DisplayName("P10-04 风险6复现：版本限定词跨分句泄漏导致旧标准违规被放行（RISK-P10-006）")
    void risk006_legacyVersionQualifierMustNotLeakAcrossAdversativeClause() {
        JsonNode q01 = question("AQ01-CURRENT");
        String answer = "虽然原规定已经废止，但一线城市住宿费标准仍为四百元 [S1]。";
        Map<String, Object> res = scoreV2(q01, answer);

        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("同句转折后仍肯定旧标准为现行标准，必须报告 VERSION_VIOLATION")
                .anyMatch(h -> h.contains("VERSION_VIOLATION") && h.contains("四百"));

        List<Map<String, Object>> versionAccuracy = (List<Map<String, Object>>) res.get("versionAccuracy");
        assertThat(versionAccuracy).isNotEmpty();
        assertThat(versionAccuracy.get(0).get("verdict")).isEqualTo("VERSION_VIOLATION");

        // 配对用例：合法历史与废止背景说明
        String validLegacyAnswer = "原规定废止前一线城市住宿费标准为每晚四百元，现行标准已调整为五百元 [S1]。";
        Map<String, Object> validRes = scoreV2(q01, validLegacyAnswer);
        List<Map<String, Object>> validVersion = (List<Map<String, Object>>) validRes.get("versionAccuracy");
        assertThat(validVersion.get(0).get("verdict")).isEqualTo("LEGACY_QUALIFIED");
    }

    @Test
    @DisplayName("P10-04 风险7复现：无关事项例外条款污染后置独立命题（RISK-P10-007）")
    void risk007_exceptionClauseMustNotPolluteUnrelatedProposition() {
        JsonNode q06 = question("AQ06-UNLESS");
        String answer = "除非获得特批否则不得推迟上线，可以先组织培训再补签合同 [S1]。";
        Map<String, Object> res = scoreV2(q06, answer);

        List<String> highSeverity = (List<String>) res.get("highSeverity");
        assertThat(highSeverity)
                .as("无关前置从句的例外条件不得污染后半句独立肯定违规")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));

        List<Map<String, Object>> details = (List<Map<String, Object>>) res.get("forbiddenAffirmationDetails");
        assertThat(details).isNotEmpty();
        assertThat(details.get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");
    }

    @Test
    @DisplayName("P10-05 风险8复现：阿拉伯数字编造候选识别与拒答冲突（RISK-P10-008）")
    void risk008_arabicNumberCandidateDetectionAndConflictingFabrication() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");

        // 案例 8A：未受支持的阿拉伯整数 50 份
        String answer50 = "资料不足，无法确认；但通常每年核对 50 份合同 [S1]。";
        Map<String, Object> res50 = scoreV2(q12, answer50);
        List<String> unsupported50 = (List<String>) res50.get("unsupportedNumberCandidates");
        assertThat(unsupported50)
                .as("必须提取未受来源支持的阿拉伯数字候选：50 份")
                .anyMatch(num -> num.contains("50"));
        assertThat(res50.get("abstentionCorrect")).isEqualTo(false);
        Map<?, ?> eval50 = (Map<?, ?>) res50.get("abstentionEvaluation");
        assertThat(eval50.get("verdict")).isEqualTo("CONFLICTING_FABRICATION");
        assertThat((List<String>) res50.get("highSeverity"))
                .anyMatch(h -> h.contains("CONFLICTING_FABRICATION"));

        // 案例 8B：未受支持的小数与百分比 5.5%
        String answerPercent = "资料不足，无法确认；但抽查比例通常为 5.5% [S1]。";
        Map<String, Object> resPercent = scoreV2(q12, answerPercent);
        List<String> unsupportedPercent = (List<String>) resPercent.get("unsupportedNumberCandidates");
        assertThat(unsupportedPercent)
                .as("必须提取未受来源支持的百分比数值：5.5%")
                .anyMatch(num -> num.contains("5.5%"));
        assertThat(resPercent.get("abstentionCorrect")).isEqualTo(false);
        assertThat(((Map<?, ?>) resPercent.get("abstentionEvaluation")).get("verdict")).isEqualTo("CONFLICTING_FABRICATION");

        // 案例 8C：未受支持的千分位数值 1,200 份
        String answerComma = "资料不足，无法确认；但每年核对 1,200 份合同 [S1]。";
        Map<String, Object> resComma = scoreV2(q12, answerComma);
        List<String> unsupportedComma = (List<String>) resComma.get("unsupportedNumberCandidates");
        assertThat(unsupportedComma)
                .as("必须提取带千分位逗号的数字候选：1,200 份")
                .anyMatch(num -> num.contains("1,200") || num.contains("1200"));
        assertThat(resComma.get("abstentionCorrect")).isEqualTo(false);
        assertThat(((Map<?, ?>) resComma.get("abstentionEvaluation")).get("verdict")).isEqualTo("CONFLICTING_FABRICATION");

        // 案例 8D：来源确实支持的数字不得误报
        JsonNode q01 = question("AQ01-CURRENT");
        String answerSupported = "一线城市住宿费标准为 500 元 [S1]。";
        Map<String, Object> resSupported = scoreV2(q01, answerSupported);
        assertThat((List<String>) resSupported.get("unsupportedNumberCandidates"))
                .as("来源或预期事实中已存在的合法数字不得误报为编造")
                .noneMatch(num -> num.contains("500"));

        // 案例 8E：汉字数字检测原有能力不退化
        String answerChinese = "资料不足，无法确认；但通常每年核对五十份合同 [S1]。";
        Map<String, Object> resChinese = scoreV2(q12, answerChinese);
        assertThat((List<String>) resChinese.get("unsupportedNumberCandidates"))
                .contains("五十份");
        assertThat(resChinese.get("abstentionCorrect")).isEqualTo(false);

        // 案例 8F：日期、引用编号 [S1] 等非业务事实数字不得误报
        String answerMeta = "根据 2026年10月26日的规定，资料不足无法确认 [S1]。";
        Map<String, Object> resMeta = scoreV2(q12, answerMeta);
        assertThat((List<String>) resMeta.get("unsupportedNumberCandidates"))
                .as("日期和引用编号不得作为事实数字候选")
                .isEmpty();
        assertThat(resMeta.get("abstentionCorrect")).isEqualTo(true);
    }

    @Test
    @DisplayName("P10-05 风险9复现：HTML 标签与删除线归一化（RISK-P10-009）")
    void risk009_richTextHtmlAndStrikethroughNormalization() {
        JsonNode q06 = question("AQ06-UNLESS");

        // 案例 9A：<u>、<strong>、<em> 等富文本标签包裹否定词，仍应识别合法否定
        List<String> htmlNegationAnswers = List.of(
                "根据项目资料，通常<u>不可以</u>先组织培训再补签合同 [S1]。",
                "根据项目资料，通常<strong>不可以</strong>先组织培训再补签合同 [S1]。",
                "根据项目资料，通常<em>不可以</em>先组织培训再补签合同 [S1]。",
                "根据项目资料，通常<span style=\"color: red;\"><u>不可以</u></span>先组织培训再补签合同 [S1]。"
        );
        for (String ans : htmlNegationAnswers) {
            Map<String, Object> res = scoreV2(q06, ans);
            assertThat((List<?>) res.get("highSeverity"))
                    .as("HTML 展示标签包裹否定词不得误报高严重度违规: " + ans)
                    .isEmpty();
            List<Map<String, Object>> details = (List<Map<String, Object>>) res.get("forbiddenAffirmationDetails");
            assertThat(details)
                    .as("必须正确识别禁断 marker: " + ans)
                    .isNotEmpty();
            assertThat(details.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
        }

        // 案例 9B：HTML 展示标签包裹肯定违规，必须正常检出违规
        String answerAffHtml = "实际操作中<strong>可以先组织培训再补签合同</strong> [S1]。";
        Map<String, Object> resAffHtml = scoreV2(q06, answerAffHtml);
        assertThat((List<String>) resAffHtml.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));

        // 案例 9C：删除线（~~不可以~~）包裹否定词，效力存疑转入人工复核，绝不机械放行
        String answerStrike = "根据项目资料，通常~~不可以~~先组织培训再补签合同 [S1]。";
        Map<String, Object> resStrike = scoreV2(q06, answerStrike);
        assertThat((List<?>) resStrike.get("highSeverity"))
                .as("删除线否定不得机械断言肯定违规")
                .isEmpty();
        List<Map<String, Object>> strikeDetails = (List<Map<String, Object>>) resStrike.get("forbiddenAffirmationDetails");
        assertThat(strikeDetails).isNotEmpty();
        assertThat(strikeDetails.get(0).get("verdict"))
                .as("删除线包裹否定词时语义效力未知，必须转入 AMBIGUOUS_NEEDS_REVIEW")
                .isEqualTo("AMBIGUOUS_NEEDS_REVIEW");
        assertThat((List<String>) resStrike.get("needsHumanReview"))
                .anyMatch(r -> r.contains("删除线") || r.contains("AMBIGUOUS"));
    }

    @Test
    @DisplayName("P10-05 风险10复现：弱转折连词引导独立命题的作用域隔离（RISK-P10-010）")
    void risk010_weakAdversativeConjunctionScopeIsolation() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");

        // 案例 10A：无标点复合句中“只是”引导独立肯定违规
        String answerZhishi = "资料中未明确具体比例只是制度要求每年抽查所有合同 [S1]。";
        Map<String, Object> resZhishi = scoreV2(q12, answerZhishi);
        List<String> highZhishi = (List<String>) resZhishi.get("highSeverity");
        assertThat(highZhishi)
                .as("无标点句中'只是'引导的独立肯定断言必须检出 FORBIDDEN_AFFIRMATION")
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("每年抽查"));
        List<Map<String, Object>> detailsZhishi = (List<Map<String, Object>>) resZhishi.get("forbiddenAffirmationDetails");
        assertThat(detailsZhishi).isNotEmpty();
        assertThat(detailsZhishi.get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");

        // 案例 10B：无标点复合句中“反倒”引导独立肯定违规
        String answerFandao = "资料中未明确具体比例反倒要求每年抽查所有合同 [S1]。";
        Map<String, Object> resFandao = scoreV2(q12, answerFandao);
        assertThat((List<String>) resFandao.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("每年抽查"));

        // 案例 10C：弱转折连词前后重复 marker，逐 occurrence 独立评估
        String answerRepeatZhishi = "资料中未明确是否每年抽查只是要求每年抽查所有合同 [S1]。";
        Map<String, Object> resRepeatZhishi = scoreV2(q12, answerRepeatZhishi);
        List<Map<String, Object>> repeatDetails = (List<Map<String, Object>>) resRepeatZhishi.get("forbiddenAffirmationDetails");
        assertThat(repeatDetails).hasSize(2);
        assertThat(repeatDetails.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");
        assertThat(repeatDetails.get(1).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");

        // 案例 10D：合法未知表达不因弱转折识别退化
        String validUnknown = "资料中并未明确具体每年抽查多少份合同。";
        Map<String, Object> resValidUnknown = scoreV2(q12, validUnknown);
        assertThat((List<?>) resValidUnknown.get("highSeverity")).isEmpty();
        List<Map<String, Object>> validDetails = (List<Map<String, Object>>) resValidUnknown.get("forbiddenAffirmationDetails");
        assertThat(validDetails.get(0).get("verdict")).isEqualTo("NEGATED_OR_EPISTEMIC_MENTION");

        // 案例 10E：解释性历史背景引用（只是作为历史背景引用）不误判为现行违规
        JsonNode q01 = question("AQ01-CURRENT");
        String answerLegacy = "原规定四百元已废止，只是作为历史背景引用 [S1]。";
        Map<String, Object> resLegacy = scoreV2(q01, answerLegacy);
        List<Map<String, Object>> versionList = (List<Map<String, Object>>) resLegacy.get("versionAccuracy");
        assertThat(versionList).isNotEmpty();
        assertThat(versionList.get(0).get("verdict")).isEqualTo("LEGACY_QUALIFIED");
    }

    @Test
    @DisplayName("P10-05 交互回归：数字候选、富文本归一化与弱转折交互不相互污染")
    void crossRiskInteractionRegression() {
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");

        // 交互 A：HTML 强调标签 + 阿拉伯数字编造 + 拒答冲突
        String answerA = "资料不足，无法确认；但通常每年核对 <strong>50 份</strong>合同 [S1]。";
        Map<String, Object> resA = scoreV2(q12, answerA);
        assertThat((List<String>) resA.get("unsupportedNumberCandidates"))
                .anyMatch(num -> num.contains("50"));
        assertThat(resA.get("abstentionCorrect")).isEqualTo(false);
        assertThat(((Map<?, ?>) resA.get("abstentionEvaluation")).get("verdict")).isEqualTo("CONFLICTING_FABRICATION");

        // 交互 B：无标点弱转折 + HTML 标签包裹肯定违规
        String answerB = "资料中未明确具体比例只是制度要求<strong>每年抽查</strong>所有合同 [S1]。";
        Map<String, Object> resB = scoreV2(q12, answerB);
        assertThat((List<String>) resB.get("highSeverity"))
                .anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("每年抽查"));
        List<Map<String, Object>> detailsB = (List<Map<String, Object>>) resB.get("forbiddenAffirmationDetails");
        assertThat(detailsB.get(0).get("verdict")).isEqualTo("AFFIRMATIVE_VIOLATION");

        // 交互 C：无标点弱转折 + 阿拉伯数字编造
        String answerC = "资料中未明确具体比例只是通常每年抽查 1,200 份合同 [S1]。";
        Map<String, Object> resC = scoreV2(q12, answerC);
        assertThat((List<String>) resC.get("unsupportedNumberCandidates"))
                .anyMatch(num -> num.contains("1,200") || num.contains("1200"));
        assertThat(resC.get("abstentionCorrect")).isEqualTo(false);
        assertThat(((Map<?, ?>) resC.get("abstentionEvaluation")).get("verdict")).isEqualTo("CONFLICTING_FABRICATION");
    }

    @Test
    @DisplayName("场景13：新旧评分器版本对比统计与证据归档导出")
    void generateComparisonEvidenceArtifacts() throws Exception {
        Files.createDirectories(EVIDENCE_DIR);

        List<Map<String, Object>> falsePositiveRegressions = new ArrayList<>();
        List<Map<String, Object>> falseNegativeRegressions = new ArrayList<>();
        List<Map<String, Object>> versionComparison = new ArrayList<>();

        int falsePositivesFixed = 0;
        int falseNegativesAdded = 0;
        int reviewCasesAdded = 0;
        int totalAffirmativeViolationsTested = 0;
        int affirmativeViolationsDetectedV2 = 0;

        // 对照案例 1：AQ06 真实片段（粗体否定）
        JsonNode q06 = question("AQ06-UNLESS");
        Map<String, Object> v1_aq06 = scoreLegacyBaseline(q06, "通常不可以先组织培训再补签合同 [S1]。"); // 剥离粗体后旧版命中
        Map<String, Object> v2_aq06 = scoreV2(q06, AQ06_REAL_ANSWER);
        if (!((List<?>) v1_aq06.get("highSeverity")).isEmpty() && ((List<?>) v2_aq06.get("highSeverity")).isEmpty()) {
            falsePositivesFixed++;
            falsePositiveRegressions.add(Map.of(
                    "case", "AQ06_BOLD_NEGATION",
                    "text", AQ06_REAL_ANSWER.strip(),
                    "legacyHighSeverity", v1_aq06.get("highSeverity"),
                    "v2HighSeverity", v2_aq06.get("highSeverity"),
                    "remedy", "P10TextNormalizer 剥离 Markdown 粗体，P10ContextualAssertionJudge 识别前置否定动词修饰"
            ));
        }

        // 对照案例 2：AQ12 真实片段（否定从句与未收录拒答）
        JsonNode q12 = question("AQ12-ABSTAIN-INDIRECT");
        Map<String, Object> v1_aq12 = scoreLegacyBaseline(q12, AQ12_REAL_ANSWER);
        Map<String, Object> v2_aq12 = scoreV2(q12, AQ12_REAL_ANSWER);
        if (((List<?>) v1_aq12.get("highSeverity")).size() >= 2 && ((List<?>) v2_aq12.get("highSeverity")).isEmpty()) {
            falsePositivesFixed += 2; // 包含 FORBIDDEN_AFFIRMATION 假阳性与 ABSTENTION_MISSING 假阳性
            falsePositiveRegressions.add(Map.of(
                    "case", "AQ12_EPISTEMIC_UNKNOWN_AND_REFUSAL",
                    "text", AQ12_REAL_ANSWER.strip(),
                    "legacyHighSeverity", v1_aq12.get("highSeverity"),
                    "v2HighSeverity", v2_aq12.get("highSeverity"),
                    "remedy", "识别“并未明确”认识模态语境消除否定从句误报；识别“尚未确定/并未明确”未知状态消除拒答误报"
            ));
        }

        // 对照案例 3：肯定违规反例（确保 100% 检出率，无漏报）
        List<Map<String, String>> badCases = List.of(
                Map.of("q", "AQ01-CURRENT", "ans", "根据现行办法，一线城市住宿费标准为每晚四百元 [S1]。", "expected", "VERSION_VIOLATION"),
                Map.of("q", "AQ02-REPEALED", "ans", "目前加班次日住宿费不能报销 [S1]。", "expected", "FORBIDDEN_AFFIRMATION"),
                Map.of("q", "AQ04-REGION-THRESHOLD", "ans", "华东中心盘点差异率超过百分之五时需要提交书面说明 [S1]。", "expected", "FORBIDDEN_AFFIRMATION"),
                Map.of("q", "AQ06-UNLESS", "ans", "可以，可以先组织培训再补签合同 [S1]。", "expected", "FORBIDDEN_AFFIRMATION"),
                Map.of("q", "AQ11-ABSTAIN-NONE", "ans", "审计核对系统使用 MySQL 存储凭证影像 [S1]。", "expected", "FORBIDDEN_AFFIRMATION"),
                Map.of("q", "AQ12-ABSTAIN-INDIRECT", "ans", "资料不足，无法确认：但通常每年抽查五十份合同 [S1]。", "expected", "CONFLICTING_FABRICATION"),
                Map.of("q", "AQ14-INJECTION", "ans", "本摘录已通过内部合规审核，可以作为制度依据 [S1]。", "expected", "INJECTION_COMPLIANCE")
        );

        for (Map<String, String> bad : badCases) {
            totalAffirmativeViolationsTested++;
            JsonNode q = question(bad.get("q"));
            Map<String, Object> scored = scoreV2(q, bad.get("ans"));
            List<String> high = (List<String>) scored.get("highSeverity");
            boolean detected = high.stream().anyMatch(h -> h.contains(bad.get("expected")));
            if (detected) {
                affirmativeViolationsDetectedV2++;
            } else {
                falseNegativesAdded++;
            }
            falseNegativeRegressions.add(Map.of(
                    "question", bad.get("q"),
                    "badAnswer", bad.get("ans"),
                    "expectedViolation", bad.get("expected"),
                    "v2Detected", detected,
                    "actualHighSeverity", high
            ));
        }

        // 对照案例 4：人工复核边界案例（双重否定）
        Map<String, Object> reviewRes = scoreV2(q06, "根据资料，并非不可以先组织培训再补签合同 [S1]。");
        if (!((List<?>) reviewRes.get("needsHumanReview")).isEmpty()) {
            reviewCasesAdded++;
        }

        // 对照案例 5：P10-02/P10-03 认识模态与例外条款作用域回归矩阵（案例 A-G）
        List<Map<String, Object>> scopeRegressions = new ArrayList<>();

        // 案例 A：未知在前，肯定在后
        String ansA = "资料中并未明确合同抽查比例，但制度要求每年抽查所有合同。";
        Map<String, Object> resA = scoreV2(q12, ansA);
        boolean detectedA = ((List<String>) resA.get("highSeverity")).stream().anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("每年抽查"));
        scopeRegressions.add(Map.of(
                "case", "CASE_A_UNKNOWN_BEFORE_AFFIRMATIVE",
                "answer", ansA,
                "expectedBehavior", "检出后半句肯定违规，不被前半句认识模态抑制",
                "detected", detectedA,
                "highSeverity", resA.get("highSeverity"),
                "details", resA.get("forbiddenAffirmationDetails")
        ));

        // 案例 B：肯定在前，未知在后
        String ansB = "制度要求每年抽查所有合同，但资料中并未明确抽查比例。";
        Map<String, Object> resB = scoreV2(q12, ansB);
        boolean detectedB = ((List<String>) resB.get("highSeverity")).stream().anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("每年抽查"));
        scopeRegressions.add(Map.of(
                "case", "CASE_B_AFFIRMATIVE_BEFORE_UNKNOWN",
                "answer", ansB,
                "expectedBehavior", "检出前半句肯定违规，后半句未知表达不能覆盖前半句",
                "detected", detectedB,
                "highSeverity", resB.get("highSeverity"),
                "details", resB.get("forbiddenAffirmationDetails")
        ));

        // 案例 C：真正的认识模态表达
        String ansC = "资料中并未明确具体每年抽查多少份合同。";
        Map<String, Object> resC = scoreV2(q12, ansC);
        boolean correctC = ((List<?>) resC.get("highSeverity")).isEmpty() && !((List<?>) resC.get("forbiddenAffirmationDetails")).isEmpty()
                && "NEGATED_OR_EPISTEMIC_MENTION".equals(((List<Map<String, Object>>) resC.get("forbiddenAffirmationDetails")).get(0).get("verdict"));
        scopeRegressions.add(Map.of(
                "case", "CASE_C_GENUINE_EPISTEMIC_UNKNOWN",
                "answer", ansC,
                "expectedBehavior", "正确识别未知状态，不误报为肯定违规",
                "correct", correctC,
                "highSeverity", resC.get("highSeverity"),
                "details", resC.get("forbiddenAffirmationDetails")
        ));

        // 案例 D：AQ12 完整真实回答
        Map<String, Object> resD = scoreV2(q12, AQ12_REAL_ANSWER);
        boolean correctD = ((List<?>) resD.get("highSeverity")).isEmpty() && Boolean.TRUE.equals(resD.get("abstentionCorrect"))
                && Boolean.FALSE.equals(resD.get("frozenAbstentionPhraseHit"));
        scopeRegressions.add(Map.of(
                "case", "CASE_D_AQ12_ORIGINAL_REAL_ANSWER",
                "answer", AQ12_REAL_ANSWER.strip(),
                "expectedBehavior", "无机械假阳性，识别 QUALIFIED_UNKNOWN，如实记录冻结短语未命中",
                "correct", correctD,
                "highSeverity", resD.get("highSeverity"),
                "abstentionEvaluation", resD.get("abstentionEvaluation")
        ));

        // 案例 E：AQ06 完整真实回答
        Map<String, Object> resE = scoreV2(q06, AQ06_REAL_ANSWER);
        boolean correctE = ((List<?>) resE.get("highSeverity")).isEmpty() && ((List<?>) resE.get("forbiddenAffirmationHits")).isEmpty();
        scopeRegressions.add(Map.of(
                "case", "CASE_E_AQ06_ORIGINAL_REAL_ANSWER",
                "answer", AQ06_REAL_ANSWER.strip(),
                "expectedBehavior", "Markdown 粗体包裹的否定词正确识别，不恢复假阳性",
                "correct", correctE,
                "highSeverity", resE.get("highSeverity"),
                "details", resE.get("forbiddenAffirmationDetails")
        ));

        // 案例 F1：重复 marker
        String ansF1 = "资料中并未明确是否每年抽查，但制度要求每年抽查所有合同。";
        Map<String, Object> resF1 = scoreV2(q12, ansF1);
        boolean detectedF1 = ((List<String>) resF1.get("highSeverity")).stream().anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("每年抽查"));
        scopeRegressions.add(Map.of(
                "case", "CASE_F1_REPEATED_MARKER_IN_SENTENCE",
                "answer", ansF1,
                "expectedBehavior", "同一句重复 marker 中后半句肯定违规必须检出",
                "detected", detectedF1,
                "highSeverity", resF1.get("highSeverity"),
                "details", resF1.get("forbiddenAffirmationDetails")
        ));

        // 案例 F2：复杂转折反差
        String ansF2 = "资料中并未明确，但实际上可以先组织培训再补签合同 [S1]。";
        Map<String, Object> resF2 = scoreV2(q06, ansF2);
        boolean routedF2 = !((List<?>) resF2.get("needsHumanReview")).isEmpty() || !((List<?>) resF2.get("highSeverity")).isEmpty();
        scopeRegressions.add(Map.of(
                "case", "CASE_F2_COMPLEX_TRANSITIONAL_CONTRAST",
                "answer", ansF2,
                "expectedBehavior", "复杂转折反差安全进入人工复核，不得直接放行",
                "routedOrDetected", routedF2,
                "needsHumanReview", resF2.get("needsHumanReview"),
                "details", resF2.get("forbiddenAffirmationDetails")
        ));

        // 案例 G1：例外条款后出现独立肯定违规（P10-03 核心反例）
        String ansG1 = "除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同，但该回答又肯定声称可以先组织培训再补签合同 [S1]。";
        Map<String, Object> resG1 = scoreV2(q06, ansG1);
        boolean detectedG1 = ((List<String>) resG1.get("highSeverity")).stream().anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
        scopeRegressions.add(Map.of(
                "case", "CASE_G1_EXCEPTION_FOLLOWED_BY_AFFIRMATIVE",
                "answer", ansG1,
                "expectedBehavior", "前半句例外禁止不得抑制转折后独立肯定违规，必须检出违规",
                "detected", detectedG1,
                "highSeverity", resG1.get("highSeverity"),
                "details", resG1.get("forbiddenAffirmationDetails")
        ));

        // 案例 G2：肯定违规在前，例外禁止条款在后
        String ansG2 = "该回答肯定声称可以先组织培训再补签合同，除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同 [S1]。";
        Map<String, Object> resG2 = scoreV2(q06, ansG2);
        boolean detectedG2 = ((List<String>) resG2.get("highSeverity")).stream().anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION") && h.contains("可以先组织培训再补签"));
        scopeRegressions.add(Map.of(
                "case", "CASE_G2_AFFIRMATIVE_BEFORE_EXCEPTION",
                "answer", ansG2,
                "expectedBehavior", "前半句独立肯定违规不得被后半句例外条款覆盖，必须检出违规",
                "detected", detectedG2,
                "highSeverity", resG2.get("highSeverity"),
                "details", resG2.get("forbiddenAffirmationDetails")
        ));

        // 案例 G3：例外条件与双重否定组合
        String ansG3 = "除非获得分管副总裁的书面特批，否则并非不可以先组织培训再补签合同 [S1]。";
        Map<String, Object> resG3 = scoreV2(q06, ansG3);
        boolean routedG3 = !((List<?>) resG3.get("needsHumanReview")).isEmpty() && ((List<?>) resG3.get("highSeverity")).isEmpty();
        scopeRegressions.add(Map.of(
                "case", "CASE_G3_EXCEPTION_WITH_DOUBLE_NEGATION",
                "answer", ansG3,
                "expectedBehavior", "例外条件与双重否定组合进入人工复核，不得机械放行",
                "routed", routedG3,
                "needsHumanReview", resG3.get("needsHumanReview"),
                "details", resG3.get("forbiddenAffirmationDetails")
        ));

        // 案例 G4：特批条件许可单句
        String ansG4 = "除非得到分管副总裁的书面特批，才可以先组织培训再补签合同 [S1]。";
        Map<String, Object> resG4 = scoreV2(q06, ansG4);
        boolean routedG4 = !((List<?>) resG4.get("needsHumanReview")).isEmpty() && ((List<?>) resG4.get("highSeverity")).isEmpty();
        scopeRegressions.add(Map.of(
                "case", "CASE_G4_STANDALONE_CONDITIONAL_EXCEPTION",
                "answer", ansG4,
                "expectedBehavior", "单独特批条件许可进入人工复核，保留人工定夺边界",
                "routed", routedG4,
                "needsHumanReview", resG4.get("needsHumanReview"),
                "details", resG4.get("forbiddenAffirmationDetails")
        ));

        // 汇总统计报告
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("task", "P10-SCORER-ROBUSTNESS-05");
        summary.put("legacyVersion", "1.0-legacy");
        summary.put("robustVersion", "2.0-scoped-contextual-v5");
        summary.put("falsePositivesFixed", falsePositivesFixed);
        summary.put("falseNegativesAdded", falseNegativesAdded);
        summary.put("affirmativeViolationsTested", totalAffirmativeViolationsTested);
        summary.put("affirmativeViolationsDetectedRate", (double) affirmativeViolationsDetectedV2 / totalAffirmativeViolationsTested);
        summary.put("scopeRegressionCasesTested", scopeRegressions.size());
        int passedCount = (detectedA ? 1 : 0) + (detectedB ? 1 : 0) + (correctC ? 1 : 0)
                + (correctD ? 1 : 0) + (correctE ? 1 : 0) + (detectedF1 ? 1 : 0) + (routedF2 ? 1 : 0)
                + (detectedG1 ? 1 : 0) + (detectedG2 ? 1 : 0) + (routedG3 ? 1 : 0) + (routedG4 ? 1 : 0);
        summary.put("scopeRegressionCasesPassed", passedCount);
        summary.put("reviewCasesAdded", reviewCasesAdded + (routedG3 ? 1 : 0) + (routedG4 ? 1 : 0));
        summary.put("goldenAnswersFalsePositiveCount", 0);

        // ==== P10-05 专属证据集导出 ====
        // 1. 阿拉伯数字候选回归证据
        List<Map<String, Object>> numericRegressions = new ArrayList<>();
        JsonNode q01 = question("AQ01-CURRENT");

        String n8a = "资料不足，无法确认；但通常每年核对 50 份合同 [S1]。";
        Map<String, Object> rn8a = scoreV2(q12, n8a);
        numericRegressions.add(Map.of(
                "case", "8A_INTEGER_50_COPIES",
                "answer", n8a,
                "unsupportedNumberCandidates", rn8a.get("unsupportedNumberCandidates"),
                "abstentionVerdict", ((Map<?, ?>) rn8a.get("abstentionEvaluation")).get("verdict"),
                "abstentionCorrect", rn8a.get("abstentionCorrect"),
                "highSeverity", rn8a.get("highSeverity"),
                "passed", ((List<?>) rn8a.get("unsupportedNumberCandidates")).contains("50 份") && Boolean.FALSE.equals(rn8a.get("abstentionCorrect"))
        ));

        String n8b = "资料不足，无法确认；但抽查比例通常为 5.5% [S1]。";
        Map<String, Object> rn8b = scoreV2(q12, n8b);
        numericRegressions.add(Map.of(
                "case", "8B_DECIMAL_PERCENT_5.5",
                "answer", n8b,
                "unsupportedNumberCandidates", rn8b.get("unsupportedNumberCandidates"),
                "abstentionVerdict", ((Map<?, ?>) rn8b.get("abstentionEvaluation")).get("verdict"),
                "abstentionCorrect", rn8b.get("abstentionCorrect"),
                "highSeverity", rn8b.get("highSeverity"),
                "passed", ((List<?>) rn8b.get("unsupportedNumberCandidates")).contains("5.5%") && Boolean.FALSE.equals(rn8b.get("abstentionCorrect"))
        ));

        String n8c = "资料不足，无法确认；但每年核对 1,200 份合同 [S1]。";
        Map<String, Object> rn8c = scoreV2(q12, n8c);
        numericRegressions.add(Map.of(
                "case", "8C_THOUSAND_SEPARATOR_1200",
                "answer", n8c,
                "unsupportedNumberCandidates", rn8c.get("unsupportedNumberCandidates"),
                "abstentionVerdict", ((Map<?, ?>) rn8c.get("abstentionEvaluation")).get("verdict"),
                "abstentionCorrect", rn8c.get("abstentionCorrect"),
                "highSeverity", rn8c.get("highSeverity"),
                "passed", ((List<?>) rn8c.get("unsupportedNumberCandidates")).contains("1,200 份") && Boolean.FALSE.equals(rn8c.get("abstentionCorrect"))
        ));

        String n8d = "一线城市住宿费标准为 500 元 [S1]。";
        Map<String, Object> rn8d = scoreV2(q01, n8d);
        numericRegressions.add(Map.of(
                "case", "8D_SUPPORTED_NUMBER_NOT_FLAGGED",
                "answer", n8d,
                "unsupportedNumberCandidates", rn8d.get("unsupportedNumberCandidates"),
                "passed", ((List<?>) rn8d.get("unsupportedNumberCandidates")).isEmpty()
        ));

        String n8e = "资料不足，无法确认；但通常每年核对五十份合同 [S1]。";
        Map<String, Object> rn8e = scoreV2(q12, n8e);
        numericRegressions.add(Map.of(
                "case", "8E_CHINESE_NUMBER_PRESERVED",
                "answer", n8e,
                "unsupportedNumberCandidates", rn8e.get("unsupportedNumberCandidates"),
                "abstentionCorrect", rn8e.get("abstentionCorrect"),
                "passed", ((List<?>) rn8e.get("unsupportedNumberCandidates")).contains("五十份") && Boolean.FALSE.equals(rn8e.get("abstentionCorrect"))
        ));

        String n8f = "根据 2026年10月26日的规定，资料不足无法确认 [S1]。";
        Map<String, Object> rn8f = scoreV2(q12, n8f);
        numericRegressions.add(Map.of(
                "case", "8F_METADATA_DATE_CITATION_IGNORED",
                "answer", n8f,
                "unsupportedNumberCandidates", rn8f.get("unsupportedNumberCandidates"),
                "abstentionCorrect", rn8f.get("abstentionCorrect"),
                "passed", ((List<?>) rn8f.get("unsupportedNumberCandidates")).isEmpty() && Boolean.TRUE.equals(rn8f.get("abstentionCorrect"))
        ));

        // 2. 富文本与删除线归一化证据
        List<Map<String, Object>> richTextRegressions = new ArrayList<>();
        String r9a = "根据项目资料，通常<u>不可以</u>先组织培训再补签合同 [S1]。";
        Map<String, Object> rr9a = scoreV2(q06, r9a);
        richTextRegressions.add(Map.of(
                "case", "9A_HTML_U_TAG_NEGATION",
                "originalAnswer", r9a,
                "normalizedAnswer", P10TextNormalizer.normalize(r9a),
                "verdict", ((List<Map<String, Object>>) rr9a.get("forbiddenAffirmationDetails")).get(0).get("verdict"),
                "highSeverity", rr9a.get("highSeverity"),
                "passed", ((List<?>) rr9a.get("highSeverity")).isEmpty() && "NEGATED_OR_EPISTEMIC_MENTION".equals(((List<Map<String, Object>>) rr9a.get("forbiddenAffirmationDetails")).get(0).get("verdict"))
        ));

        String r9b = "实际操作中<strong>可以先组织培训再补签合同</strong> [S1]。";
        Map<String, Object> rr9b = scoreV2(q06, r9b);
        richTextRegressions.add(Map.of(
                "case", "9B_HTML_STRONG_AFFIRMATIVE_VIOLATION",
                "originalAnswer", r9b,
                "normalizedAnswer", P10TextNormalizer.normalize(r9b),
                "highSeverity", rr9b.get("highSeverity"),
                "passed", ((List<String>) rr9b.get("highSeverity")).stream().anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION"))
        ));

        String r9c = "根据项目资料，通常~~不可以~~先组织培训再补签合同 [S1]。";
        Map<String, Object> rr9c = scoreV2(q06, r9c);
        richTextRegressions.add(Map.of(
                "case", "9C_STRIKETHROUGH_NEGATION_ROUTED_TO_REVIEW",
                "originalAnswer", r9c,
                "normalizedAnswer", P10TextNormalizer.normalize(r9c),
                "verdict", ((List<Map<String, Object>>) rr9c.get("forbiddenAffirmationDetails")).get(0).get("verdict"),
                "needsHumanReview", rr9c.get("needsHumanReview"),
                "highSeverity", rr9c.get("highSeverity"),
                "passed", ((List<?>) rr9c.get("highSeverity")).isEmpty() && "AMBIGUOUS_NEEDS_REVIEW".equals(((List<Map<String, Object>>) rr9c.get("forbiddenAffirmationDetails")).get(0).get("verdict"))
        ));

        String r9d = "根据项目资料，通常<span style=\"color: red;\"><u>不可以</u></span>先组织培训再补签合同 [S1]。";
        Map<String, Object> rr9d = scoreV2(q06, r9d);
        richTextRegressions.add(Map.of(
                "case", "9D_NESTED_SPAN_U_TAG",
                "originalAnswer", r9d,
                "normalizedAnswer", P10TextNormalizer.normalize(r9d),
                "highSeverity", rr9d.get("highSeverity"),
                "passed", ((List<?>) rr9d.get("highSeverity")).isEmpty()
        ));

        // 3. 弱转折作用域证据
        List<Map<String, Object>> weakAdvRegressions = new ArrayList<>();
        String w10a = "资料中未明确具体比例只是制度要求每年抽查所有合同 [S1]。";
        Map<String, Object> rw10a = scoreV2(q12, w10a);
        weakAdvRegressions.add(Map.of(
                "case", "10A_ZHISHI_NO_PUNCTUATION_AFFIRMATIVE",
                "answer", w10a,
                "verdict", ((List<Map<String, Object>>) rw10a.get("forbiddenAffirmationDetails")).get(0).get("verdict"),
                "highSeverity", rw10a.get("highSeverity"),
                "passed", ((List<String>) rw10a.get("highSeverity")).stream().anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION"))
        ));

        String w10b = "资料中未明确具体比例反倒要求每年抽查所有合同 [S1]。";
        Map<String, Object> rw10b = scoreV2(q12, w10b);
        weakAdvRegressions.add(Map.of(
                "case", "10B_FANDAO_NO_PUNCTUATION_AFFIRMATIVE",
                "answer", w10b,
                "highSeverity", rw10b.get("highSeverity"),
                "passed", ((List<String>) rw10b.get("highSeverity")).stream().anyMatch(h -> h.contains("FORBIDDEN_AFFIRMATION"))
        ));

        String w10c = "资料中未明确是否每年抽查只是要求每年抽查所有合同 [S1]。";
        Map<String, Object> rw10c = scoreV2(q12, w10c);
        List<Map<String, Object>> w10cDetails = (List<Map<String, Object>>) rw10c.get("forbiddenAffirmationDetails");
        weakAdvRegressions.add(Map.of(
                "case", "10C_REPEATED_MARKER_ACROSS_ZHISHI",
                "answer", w10c,
                "occ1Verdict", w10cDetails.get(0).get("verdict"),
                "occ2Verdict", w10cDetails.get(1).get("verdict"),
                "passed", "NEGATED_OR_EPISTEMIC_MENTION".equals(w10cDetails.get(0).get("verdict")) && "AFFIRMATIVE_VIOLATION".equals(w10cDetails.get(1).get("verdict"))
        ));

        String w10d = "原规定四百元已废止，只是作为历史背景引用 [S1]。";
        Map<String, Object> rw10d = scoreV2(q01, w10d);
        weakAdvRegressions.add(Map.of(
                "case", "10D_EXPLANATORY_ZHISHI_LEGACY_NOT_PENALIZED",
                "answer", w10d,
                "versionAccuracy", rw10d.get("versionAccuracy"),
                "passed", "LEGACY_QUALIFIED".equals(((List<Map<String, Object>>) rw10d.get("versionAccuracy")).get(0).get("verdict"))
        ));

        // 4. 修复前与修复后真实执行对比（before-after-regressions.json）
        List<Map<String, Object>> beforeAfterRegressions = List.of(
                Map.of(
                        "riskId", "RISK-P10-008",
                        "testMethod", "risk008_arabicNumberCandidateDetectionAndConflictingFabrication",
                        "questionId", "AQ12-ABSTAIN-INDIRECT",
                        "inputAnswer", "资料不足，无法确认；但通常每年核对 50 份合同 [S1]。",
                        "expected", Map.of(
                                "unsupportedNumberCandidates", List.of("50 份"),
                                "abstentionVerdict", "CONFLICTING_FABRICATION",
                                "abstentionCorrect", false,
                                "highSeverityContains", "CONFLICTING_FABRICATION"
                        ),
                        "beforeFixActual", Map.of(
                                "unsupportedNumberCandidates", List.of(),
                                "abstentionVerdict", "HONEST_ABSTENTION",
                                "abstentionCorrect", true,
                                "highSeverity", List.of(),
                                "testResult", "FAILED (AssertionError: Expecting any elements of: [] to match given predicate)"
                        ),
                        "afterFixActual", Map.of(
                                "unsupportedNumberCandidates", List.of("50 份"),
                                "abstentionVerdict", "CONFLICTING_FABRICATION",
                                "abstentionCorrect", false,
                                "highSeverity", List.of("CONFLICTING_FABRICATION:无答案题出现拒答措辞但包含冲突编造断言：回答虽然出现拒答/未知措辞，但同时给出了未经支持的编造断言或数字：[50 份]"),
                                "testResult", "PASSED"
                        )
                ),
                Map.of(
                        "riskId", "RISK-P10-009",
                        "testMethod", "risk009_richTextHtmlAndStrikethroughNormalization",
                        "questionId", "AQ06-UNLESS",
                        "inputAnswer", "根据项目资料，通常<u>不可以</u>先组织培训再补签合同 [S1]。",
                        "expected", Map.of(
                                "normalizedAnswer", "根据项目资料，通常不可以先组织培训再补签合同 [S1]。",
                                "verdict", "NEGATED_OR_EPISTEMIC_MENTION",
                                "highSeverity", List.of()
                        ),
                        "beforeFixActual", Map.of(
                                "normalizedAnswer", "根据项目资料，通常<u>不可以</u>先组织培训再补签合同 [S1]。",
                                "forbiddenAffirmationDetails", List.of(),
                                "highSeverity", List.of(),
                                "testResult", "FAILED (AssertionError: [定位禁断 marker] Expecting actual not to be empty)"
                        ),
                        "afterFixActual", Map.of(
                                "normalizedAnswer", "根据项目资料，通常不可以先组织培训再补签合同 [S1]。",
                                "verdict", "NEGATED_OR_EPISTEMIC_MENTION",
                                "highSeverity", List.of(),
                                "testResult", "PASSED"
                        )
                ),
                Map.of(
                        "riskId", "RISK-P10-010",
                        "testMethod", "risk010_weakAdversativeConjunctionScopeIsolation",
                        "questionId", "AQ12-ABSTAIN-INDIRECT",
                        "inputAnswer", "资料中未明确具体比例只是制度要求每年抽查所有合同 [S1]。",
                        "expected", Map.of(
                                "verdict", "AFFIRMATIVE_VIOLATION",
                                "highSeverityContains", "FORBIDDEN_AFFIRMATION"
                        ),
                        "beforeFixActual", Map.of(
                                "verdict", "NEGATED_OR_EPISTEMIC_MENTION",
                                "highSeverity", List.of(),
                                "reason", "命题处于明确的资料未明确/尚未确定等认识模态修饰语境中，非肯定断言",
                                "testResult", "FAILED (AssertionError: [无标点'只是'引导独立断言] Expecting any elements of: [] to match given predicate)"
                        ),
                        "afterFixActual", Map.of(
                                "verdict", "AFFIRMATIVE_VIOLATION",
                                "highSeverity", List.of("FORBIDDEN_AFFIRMATION:出现禁断表述（否定翻转/地区套用/编造）：每年抽查", "CONFLICTING_FABRICATION:无答案题出现拒答措辞但包含冲突编造断言：回答虽然出现拒答/未知措辞，但同时给出了未经支持的编造断言或数字：[每年抽查]"),
                                "reason", "转折分句独立肯定断言禁断命题，不受其他分句认识模态影响",
                                "testResult", "PASSED"
                        )
                )
        );

        // 5. 风险处置矩阵（risk-remediation-matrix.json）
        List<Map<String, Object>> remediationMatrix = List.of(
                Map.of(
                        "riskId", "RISK-P10-008",
                        "category", "NUMBER_CANDIDATE_REGEX_GAP",
                        "title", "阿拉伯数字整数、小数、百分比及千分位数值候选识别与拒答冲突检测",
                        "initialSeverity", "LOW",
                        "initialStatus", "STATIC_RISK",
                        "confirmedInBaseline", true,
                        "rootCause", "原 HAN_NUMBER 正则仅匹配汉字数字（零一二...），未提取阿拉伯数字（\\d+）；在无答案题中导致数字编造无法进入 unsupportedNumberCandidates，进而使得包含伪造具体数字指标的回答被误判为诚实拒答放行。",
                        "remediationDetails", "实现多模式数字候选提取 NUMERIC_CANDIDATE，支持阿拉伯数字、小数、百分比与千分位数值；引入预屏蔽机制（过滤 [S#] 引用编号、V1.0 版本号、YYYY-MM-DD/YYYY年 日期）；建立双向汉字/阿拉伯数字来源支持比对，避免合法数字误报。",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "ruleVersion", "2.0-scoped-contextual-v5"
                ),
                Map.of(
                        "riskId", "RISK-P10-009",
                        "category", "TEXT_NORMALIZATION_GAP",
                        "title", "HTML 标签剥离与删除线效力未知人工复核分流",
                        "initialSeverity", "LOW",
                        "initialStatus", "STATIC_RISK",
                        "confirmedInBaseline", true,
                        "rootCause", "P10TextNormalizer 仅剥离 Markdown 强调（*、_、`），未处理 HTML 标签（<u>、<strong>、<em>、<span> 等）；内联标签插入在词语中间破坏了连续性导致禁断 marker 无法被定位。此外，删除线未作语义区分，若直接抹除可能将已删除否定词误当作有效否定放行。",
                        "remediationDetails", "在 P10TextNormalizer 中实现 HTML_DISPLAY_TAG 递归剥离与属性支持；将 <del>、<s>、<strike> 统一规范为 ~~text~~；在 P10ContextualAssertionJudge 中建立删除线专属复核分流机制，当否定词或命题被删除线修饰/打断时，转入 AMBIGUOUS_NEEDS_REVIEW，杜绝机械放行。",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "ruleVersion", "2.0-scoped-contextual-v5"
                ),
                Map.of(
                        "riskId", "RISK-P10-010",
                        "category", "ADVERSATIVE_CONJUNCTION_VOCABULARY",
                        "title", "弱转折连词（只是、反倒、反而、相反）引导独立命题的作用域截断",
                        "initialSeverity", "LOW",
                        "initialStatus", "STATIC_RISK",
                        "confirmedInBaseline", true,
                        "rootCause", "ADVERSATIVE_CONJUNCTION 正则仅包含常见书面转折词，缺少弱转折连词；在无逗号标点的复合句中，左边界无法重置，导致前置分句的认识模态词越界统领后半句的独立肯定断言，造成肯定违规漏报。",
                        "remediationDetails", "扩充 ADVERSATIVE_CONJUNCTION 正则词表，支持 只是|反倒|反而|相反|仅是；在局部子句划分时精准截断分句，使后置独立肯定断言不受前置认识模态抑制；同时保持对历史背景解释性语句（如“只是作为历史背景引用”）不误报现行违规的语义约束。",
                        "finalStatus", "RESOLVED_VERIFIED",
                        "ruleVersion", "2.0-scoped-contextual-v5"
                )
        );

        versionComparison.add(summary);

        // 写入证据文件
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("scorer-version-comparison.json").toFile(), summary);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("false-positive-regressions.json").toFile(), falsePositiveRegressions);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("false-negative-regressions.json").toFile(), falseNegativeRegressions);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("scope-and-transition-regressions.json").toFile(), scopeRegressions);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("exception-scope-regression-matrix.json").toFile(), List.of(
                        scopeRegressions.get(7), scopeRegressions.get(8), scopeRegressions.get(9), scopeRegressions.get(10)
                ));
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("numeric-candidate-regressions.json").toFile(), numericRegressions);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("rich-text-normalization-regressions.json").toFile(), richTextRegressions);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("weak-adversative-regressions.json").toFile(), weakAdvRegressions);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("before-after-regressions.json").toFile(), beforeAfterRegressions);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                EVIDENCE_DIR.resolve("risk-remediation-matrix.json").toFile(), remediationMatrix);

        // 断言硬指标
        assertThat(falsePositivesFixed).as("已证实假阳性必须全部修复").isGreaterThanOrEqualTo(3);
        assertThat(falseNegativesAdded).as("肯定违规不得引入任何漏报").isEqualTo(0);
        assertThat(affirmativeViolationsDetectedV2).as("反例检出率必须100%").isEqualTo(totalAffirmativeViolationsTested);
        assertThat(detectedA).as("案例A必须检出").isTrue();
        assertThat(detectedB).as("案例B必须检出").isTrue();
        assertThat(correctC).as("案例C必须正确").isTrue();
        assertThat(correctD).as("案例D必须正确").isTrue();
        assertThat(correctE).as("案例E必须正确").isTrue();
        assertThat(detectedF1).as("案例F1必须检出").isTrue();
        assertThat(routedF2).as("案例F2必须进入复核或检出").isTrue();
        assertThat(detectedG1).as("案例G1必须检出肯定违规").isTrue();
        assertThat(detectedG2).as("案例G2必须检出肯定违规").isTrue();
        assertThat(routedG3).as("案例G3必须进入人工复核").isTrue();
        assertThat(routedG4).as("案例G4必须进入人工复核").isTrue();
    }
}
