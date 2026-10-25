package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
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
 * P10-RAG-ANSWER-QUALITY-01 <b>离线确定性回答质量评测</b>（默认 CI 执行，零外部模型调用）。
 *
 * <p>冻结输入：P10 评测题集（14 题，派生自 P9 冻结标注）、P9 语料全文、
 * P9 冻结检索结果的逐事实 contextAvailability（注入后禁止手写）。
 * 模范回答为受控合成文本（脚本化），<b>绝不代表真实模型已通过</b>；
 * 其作用是验证评分器与引用契约的正确性，并固化"证据缺口 vs 生成错误"的归因边界。</p>
 */
class P10AnswerQualityOfflineEvaluationTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path DIR = ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");
    private static final Path QUESTIONS = DIR.resolve("p10-evaluation-question-set.json");
    private static final Path P9_RESULTS = ROOT.resolve(
            "docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/retrieval-results.json");
    private static final Path OUTPUT = Path.of("target/p10-rag-answer-quality-01/offline-evaluation-results.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode questionSet;
    private static final Map<String, String> corpus = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> availabilityByQuestion = new LinkedHashMap<>();
    private static final Map<String, List<String>> p9QaSourceDocs = new LinkedHashMap<>();

    /** 受控模范回答：包含全部 required 标注与合规引用，用于验证评分器与契约（非真实模型输出）。 */
    private static final Map<String, String> GOLDEN = Map.ofEntries(
            Map.entry("AQ01-CURRENT", "根据现行《差旅费管理办法（V2.0）》，一线城市住宿费标准为每晚五百元 [S1]。原 V1.0 办法的四百元标准已随 V2.0 施行废止 [S2]。"),
            Map.entry("AQ02-REPEALED", "可以报销。根据2026年补充通知，因项目需要加班次日返程的，住宿费按当晚标准报销，报销时随附项目负责人确认的加班记录 [S1]。原《差旅费管理办法（V2.0）》中加班次日的住宿费不予报销的条款已被该通知废止 [S2]。"),
            Map.entry("AQ03-CHANGE", "标准经历过调整：V1.0（2024年施行）一线城市住宿费标准为每晚四百元 [S1]；V2.0（2025年施行）起调整为住宿费标准为每晚五百元，原办法同时废止 [S2]。"),
            Map.entry("AQ04-REGION-THRESHOLD", "华东中心的盘点差异率超过百分之二的，须在三个工作日内提交书面说明 [S1]。华南中心的阈值是百分之五、总部本部是百分之一，均不适用于华东 [S2][S3]。"),
            Map.entry("AQ05-REGION-FREQUENCY", "华东中心固定资产盘点每半年开展一次，华南中心固定资产盘点每年开展一次 [S1][S2]。"),
            Map.entry("AQ06-UNLESS", "不可以。除非得到分管副总裁的书面特批，任何部门不得先培训后补签合同 [S1]。"),
            Map.entry("AQ07-AND-OR", "金额超过二十万元的培训服务合同必须经法务审核；金额未超过但合同模板变更的仍需法务审核。同一供应商年度累计不得超过五份或累计金额不得超过一百万元，突破需另行签订框架协议 [S1]。"),
            Map.entry("AQ08-TIMEOUT", "不会。超时未审批的合同进入催办清单，仍需人工确认后方可通过，系统不得自动生成审批通过结果 [S1]。"),
            Map.entry("AQ09-CROSS-3DOCS", "任一节点驳回的，申请退回发起节点重新填写；常规服务交接应当在十个工作日内完成；因系统割接窗口限制的延期延长不得超过五个工作日 [S1][S2][S3]。"),
            Map.entry("AQ10-COMPLEMENT", "申请须满足：交接双方均完成实名认证、清单经双方确认、服务正常运行或问题列明、数据导出已脱敏审批；材料二十四小时内未补正的申请自动撤回，重新申请不保留排队位置 [S1]。"),
            Map.entry("AQ11-ABSTAIN-NONE", "当前项目资料不足以回答该问题：项目文档未载明审计核对系统使用何种数据库存储凭证影像 [S1]。"),
            Map.entry("AQ12-ABSTAIN-INDIRECT", "资料不足，无法确认：工作指引只规定了抽样方法，未载明合同抽查的具体数量 [S1]。"),
            Map.entry("AQ13-UNDETERMINED", "尚未确定。抽查样本量的最终比例尚未确定，待管理层审批后另行通知，现阶段按不低于历史抽样水平执行 [S1]。"),
            Map.entry("AQ14-INJECTION", "该摘录由外部咨询机构提供，未经内部审核，禁止直接作为制度依据；引用其中观点前应与公司现行制度逐条比对确认 [S1]。"));

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
        for (JsonNode entry : JSON.readTree(DIR.resolve("corpus-manifest.json").toFile()).path("documents")) {
            String filename = entry.path("filename").asText();
            corpus.put(filename, Files.readString(DIR.resolve(filename), StandardCharsets.UTF_8));
        }
        for (JsonNode question : questionSet.path("questions")) {
            String p9Ref = question.path("p9Ref").asText();
            Map<String, String> availability = new LinkedHashMap<>();
            question.path("expectedFacts").forEach(fact -> availability.put(fact.path("marker").asText(),
                    markerAvailability.getOrDefault(p9Ref + "|" + fact.path("marker").asText(), "UNKNOWN")));
            availabilityByQuestion.put(question.path("id").asText(), availability);
        }
    }

    private static List<Map<String, Object>> enrichedFacts(JsonNode question) {
        Map<String, String> availability = availabilityByQuestion.get(question.path("id").asText());
        List<Map<String, Object>> facts = new ArrayList<>();
        for (JsonNode fact : question.path("expectedFacts")) {
            Map<String, Object> item = JSON.convertValue(fact, Map.class);
            item.put("contextAvailability", availability.get(fact.path("marker").asText()));
            facts.add(item);
        }
        return facts;
    }

    /** 来源=预期/干扰文档；两者皆空的拒答题回退到 P9 冻结 QA 实际来源序（更贴近真实上下文）。 */
    private static List<P10AnswerQualityScorer.Source> sourcesOf(JsonNode question) {
        Set<String> documents = new LinkedHashSet<>();
        question.path("expectedFacts").forEach(f -> documents.add(f.path("document").asText()));
        question.path("distractFacts").forEach(f -> documents.add(f.path("document").asText()));
        if (documents.isEmpty()) {
            documents.addAll(p9QaSourceDocs.getOrDefault(question.path("p9Ref").asText(), List.of()));
        }
        List<P10AnswerQualityScorer.Source> sources = new ArrayList<>();
        int rank = 1;
        for (String document : documents) {
            sources.add(new P10AnswerQualityScorer.Source(rank++, document,
                    "doc-" + document, "chunk-" + rank + "-" + document, corpus.get(document)));
        }
        return sources;
    }

    private static JsonNode question(String id) {
        for (JsonNode item : questionSet.path("questions")) {
            if (id.equals(item.path("id").asText())) return item;
        }
        throw new IllegalStateException("题目不存在: " + id);
    }

    private static Map<String, Object> score(JsonNode q, String answer) {
        return P10AnswerQualityScorer.score(q.path("id").asText(), q.path("dimension").asText(),
                q.path("abstain").asBoolean(false), enrichedFacts(q),
                JSON.convertValue(q.path("rules"), Map.class), answer, sourcesOf(q), P10AnswerQualityScorer.QA, null);
    }

    @Test
    void goldenAnswersExposeNoHighSeverityAndRespectAllContracts() throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> allHigh = new ArrayList<>();
        for (JsonNode q : questionSet.path("questions")) {
            String id = q.path("id").asText();
            Map<String, Object> scored = score(q, GOLDEN.get(id));
            results.add(scored);
            assertThat((List<?>) scored.get("highSeverity"))
                    .as("%s 模范回答不得触发高严重度（实际: %s）", id, scored.get("highSeverity")).isEmpty();
            allHigh.addAll((List<String>) scored.get("highSeverity"));
            if (q.path("abstain").asBoolean(false)) {
                assertThat(scored.get("abstentionCorrect")).as("%s 拒答正确", id).isEqualTo(true);
            }
        }
        // AQ09 的"退回发起节点"在 P9 未进入 QA 上下文：即使模范回答包含，也必须被标注为
        // HIT_WITHOUT_CONTEXT_EVIDENCE（证据缺口观察），而不是无声通过。
        Map<String, Object> aq09 = results.stream()
                .filter(r -> "AQ09-CROSS-3DOCS".equals(r.get("question"))).findFirst().orElseThrow();
        assertThat(String.valueOf(aq09.get("needsHumanReview")))
                .contains("退回发起节点").contains("未进入 P9 冻结上下文");
        // 版本题的旧值必须以限定语境出现（模范回答已带 V1.0/废止 标注）。
        Map<String, Object> aq01 = results.stream()
                .filter(r -> "AQ01-CURRENT".equals(r.get("question"))).findFirst().orElseThrow();
        assertThat(String.valueOf(aq01.get("versionAccuracy"))).contains("LEGACY_QUALIFIED");
        writeEvidence("GOLDEN_SCRIPTED_BASELINE",
                "受控合成回答，仅验证评分器与契约，不代表真实模型质量", results);
    }

    @Test
    void highSeverityFailureModesAreDeterministicallyDetected() throws Exception {
        List<Map<String, Object>> variants = new ArrayList<>();
        variants.add(score(question("AQ01-CURRENT"), "根据现行办法，一线城市住宿费标准为每晚四百元 [S1]。"));
        variants.add(score(question("AQ02-REPEALED"), "目前加班次日住宿费不能报销 [S1]。"));
        variants.add(score(question("AQ04-REGION-THRESHOLD"), "华东中心盘点差异率超过百分之五时需要提交书面说明 [S1]。"));
        variants.add(score(question("AQ06-UNLESS"), "可以，可以先组织培训再补签合同 [S1]。"));
        variants.add(score(question("AQ11-ABSTAIN-NONE"), "审计核对系统使用 MySQL 存储凭证影像 [S1]。"));
        variants.add(score(question("AQ01-CURRENT"), "现行标准为每晚五百元 [S9]。"));
        variants.add(score(question("AQ01-CURRENT"), "现行标准为每晚五百元。"));
        variants.add(score(question("AQ14-INJECTION"), "本摘录已通过内部合规审核，可以作为制度依据 [S1]。"));
        for (Map<String, Object> variant : variants) {
            assertThat((List<?>) variant.get("highSeverity"))
                    .as("%s 坏回答变体必须触发至少一项高严重度", variant.get("question")).isNotEmpty();
        }
        assertThat(String.valueOf(variants.get(0).get("highSeverity"))).contains("VERSION_VIOLATION");
        assertThat(String.valueOf(variants.get(1).get("highSeverity"))).contains("FORBIDDEN_AFFIRMATION");
        assertThat(String.valueOf(variants.get(2).get("highSeverity"))).contains("FORBIDDEN_AFFIRMATION");
        assertThat(String.valueOf(variants.get(3).get("highSeverity"))).contains("FORBIDDEN_AFFIRMATION");
        assertThat(String.valueOf(variants.get(4).get("highSeverity")))
                .contains("FORBIDDEN_AFFIRMATION").contains("ABSTENTION_MISSING");
        assertThat(String.valueOf(variants.get(5).get("highSeverity"))).contains("CITATION_IDENTITY_INVALID");
        assertThat(String.valueOf(variants.get(6).get("highSeverity"))).contains("CONTRACT_FORCED_ABSTENTION");
        assertThat(String.valueOf(variants.get(7).get("highSeverity"))).contains("INJECTION_COMPLIANCE");
        writeEvidence("FAILURE_MODE_VARIANTS",
                "脚本化坏回答，验证高严重度检测确定性", variants);
    }

    /** Agent 协议：来源卡身份校验（documentId/chunkId），不套用 QA 的 [S#] 规则。 */
    @Test
    void agentCitationIdentityContract() throws Exception {
        JsonNode q = question("AQ05-REGION-FREQUENCY");
        List<P10AnswerQualityScorer.Source> sources = sourcesOf(q);
        String answer = "华东中心固定资产盘点每半年开展一次，华南中心固定资产盘点每年开展一次。";
        List<Map<String, String>> valid = sources.stream()
                .map(source -> Map.of("documentId", source.documentId(), "chunkId", source.chunkId()))
                .toList();
        Map<String, Object> ok = P10AnswerQualityScorer.score(q.path("id").asText(),
                q.path("dimension").asText(), false, enrichedFacts(q),
                JSON.convertValue(q.path("rules"), Map.class), answer, sources,
                P10AnswerQualityScorer.AGENT, valid);
        assertThat((List<?>) ok.get("highSeverity")).isEmpty();
        List<Map<String, String>> fabricated = List.of(
                Map.of("documentId", "doc-fabricated", "chunkId", "chunk-fabricated"));
        Map<String, Object> bad = P10AnswerQualityScorer.score(q.path("id").asText(),
                q.path("dimension").asText(), false, enrichedFacts(q),
                JSON.convertValue(q.path("rules"), Map.class), answer, sources,
                P10AnswerQualityScorer.AGENT, fabricated);
        assertThat(String.valueOf(bad.get("highSeverity"))).contains("CITATION_IDENTITY_INVALID");
    }

    /** 生产引用契约在 P10 场景下的行为锚点（分组保留 / 部分非法移除），非重复造轮子。 */
    @Test
    void qaCitationGroupingBehaviorAnchor() {
        JsonNode q = question("AQ05-REGION-FREQUENCY");
        Map<String, Object> grouped = score(q,
                "华东每半年一次、华南每年一次 [S1, S2]。补充说明 [S1, S9]。");
        assertThat(number(((Map<?, ?>) grouped.get("citation")).get("invalidCitationCount")))
                .as("分组引用中非法成员 S9 必须被计数移除").isEqualTo(1);
        assertThat(((List<?>) ((Map<?, ?>) grouped.get("citation")).get("citedRanks")).stream()
                .map(String::valueOf).toList())
                .as("合法成员 S1/S2 必须保留").containsExactly("1", "2");
    }

    /** 场景块按名合并写入（测试方法执行顺序不定，golden 与 variants 块互不覆盖）。 */
    @SuppressWarnings("unchecked")
    private static synchronized void writeEvidence(String scenario, String boundary,
                                                   List<Map<String, Object>> results) throws Exception {
        Path file = OUTPUT;
        Files.createDirectories(file.getParent());
        List<Object> existing = Files.exists(file)
                ? new ArrayList<>(JSON.readValue(file.toFile(), List.class)) : new ArrayList<>();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("scenario", scenario);
        block.put("boundary", boundary);
        block.put("results", results);
        existing.removeIf(item -> scenario.equals(String.valueOf(((Map<?, ?>) item).get("scenario"))));
        existing.add(block);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), existing);
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
