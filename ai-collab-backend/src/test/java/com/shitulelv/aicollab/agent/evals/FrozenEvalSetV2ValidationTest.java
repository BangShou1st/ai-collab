package com.shitulelv.aicollab.agent.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结评测集 v2（{@code /evals/frozen-eval-set-v2.json}）只读校验（无需 Docker）：
 * <ul>
 *   <li>schema 完整性：每条 case 键齐备、枚举合法、case_id 唯一；</li>
 *   <li>桶覆盖统计：四桶数量守门（14/12/11/9，共 46）与 dev/holdout 分层比例；</li>
 *   <li>继承保真：golden 来源 payload 可原样通过既有 {@link GoldenCaseLoader} 加载路径；
 *       RAG 来源的 factSources 锚点必须仍是 P9 冻结语料的逐字子串；business 来源绑定的
 *       集成测试类必须真实存在。</li>
 * </ul>
 * 本测试不改动任何生产代码与资产，仅冻结集自身的完整性门禁。
 */
class FrozenEvalSetV2ValidationTest {

    private static final String RESOURCE = "/evals/frozen-eval-set-v2.json";
    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path CORPUS_DIR = REPO_ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");
    private static final Path BACKEND_TEST_JAVA = REPO_ROOT.resolve("ai-collab-backend/src/test/java");

    private static final Set<String> BUCKETS = Set.of(
            "RAG_QA", "TOOL_CALLING", "LONG_CONVERSATION", "PLANNING_APPROVAL");
    private static final Set<String> SPLITS = Set.of("dev", "holdout");
    private static final Set<String> REVIEW_STATUSES = Set.of("HUMAN_REVIEWED", "PENDING_REVIEW");
    private static final Set<String> SOURCE_ASSETS = Set.of(
            "golden-cases.json", "p10-evaluation-question-set.json", "business-evaluation-cases.json");

    /** 每条 case 必须存在的键（值允许 null 的键见 schemaNotes；核心身份键不得为 null）。 */
    private static final List<String> REQUIRED_KEYS = List.of(
            "case_id", "bucket", "split", "reviewStatus", "sourceAsset", "sourceRef",
            "question", "factSources", "expectedTools", "forbiddenTools", "citationTarget",
            "allowedResultRange", "modelProfile", "commitSha", "notes", "payload");
    private static final List<String> NON_NULL_KEYS = List.of(
            "case_id", "bucket", "split", "reviewStatus", "sourceAsset", "sourceRef",
            "question", "allowedResultRange", "commitSha", "payload");

    /** 桶覆盖守门：AC-P0 §6.2 继承方案的固定数量。 */
    private static final Map<String, Integer> BUCKET_COUNTS = Map.of(
            "RAG_QA", 14, "TOOL_CALLING", 12, "LONG_CONVERSATION", 11, "PLANNING_APPROVAL", 9);
    private static final Map<String, Integer> HOLDOUT_COUNTS = Map.of(
            "RAG_QA", 6, "TOOL_CALLING", 0, "LONG_CONVERSATION", 4, "PLANNING_APPROVAL", 3);

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode loadFrozenSet() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            assertThat(in).as("冻结集资源 " + RESOURCE).isNotNull();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // fixtures 硬规则：UTF-8 无 BOM
            assertThat(raw.startsWith("\uFEFF")).as("冻结集不得带 UTF-8 BOM").isFalse();
            return json.readTree(raw);
        }
    }

    @Test
    void frozenSetLoadsFortySixCasesAcrossAllBuckets() throws IOException {
        JsonNode root = loadFrozenSet();
        assertThat(root.path("version").asInt()).isEqualTo(2);
        assertThat(root.path("frozenAt").asText()).isNotBlank();
        assertThat(root.path("holdoutPolicy").asText()).contains("不得");

        // 固化环境：运行 SHA 与语料指纹必须存在
        JsonNode env = root.path("frozenEnvironment");
        assertThat(env.path("commitSha").asText()).hasSize(40);
        assertThat(env.path("corpusFingerprint").path("corpusManifestSha256").asText()).hasSize(64);
        assertThat(env.path("indexFingerprint").asText()).isNotBlank();
        // 模型配置/温度/费率版本如实标 NOT_VERIFIED（不出站真实模型）
        assertThat(env.path("modelProfile").path("status").asText()).isEqualTo("NOT_BOUND");

        JsonNode cases = root.path("cases");
        assertThat(cases.isArray()).isTrue();
        assertThat(cases.size()).as("冻结集总条数").isEqualTo(46);

        Set<String> seenIds = new HashSet<>();
        Map<String, Integer> bucketCounts = new HashMap<>();
        Map<String, Integer> holdoutCounts = new HashMap<>();
        Map<String, Integer> reviewCounts = new HashMap<>();
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i);
            for (String key : REQUIRED_KEYS) {
                assertThat(c.has(key)).as("$.cases[%d] 缺少键 %s", i, key).isTrue();
            }
            for (String key : NON_NULL_KEYS) {
                assertThat(c.path(key).isNull() || c.path(key).isMissingNode())
                        .as("$.cases[%d].%s 不得为 null", i, key).isFalse();
            }
            String caseId = c.path("case_id").asText();
            assertThat(caseId).matches("[A-Za-z0-9][A-Za-z0-9._-]*");
            assertThat(seenIds.add(caseId)).as("case_id 重复: %s", caseId).isTrue();

            assertThat(c.path("bucket").asText()).isIn(BUCKETS);
            assertThat(c.path("split").asText()).isIn(SPLITS);
            assertThat(c.path("reviewStatus").asText()).isIn(REVIEW_STATUSES);
            assertThat(c.path("sourceAsset").asText()).isIn(SOURCE_ASSETS);
            assertThat(c.path("sourceRef").asText()).isNotBlank();
            assertThat(c.path("question").asText()).isNotBlank();
            assertThat(c.path("commitSha").asText()).hasSize(40);
            assertThat(c.path("payload").isObject()).isTrue();

            bucketCounts.merge(c.path("bucket").asText(), 1, Integer::sum);
            if ("holdout".equals(c.path("split").asText())) {
                holdoutCounts.merge(c.path("bucket").asText(), 1, Integer::sum);
            }
            reviewCounts.merge(c.path("reviewStatus").asText(), 1, Integer::sum);
        }

        // 桶覆盖统计守门
        assertThat(bucketCounts).as("桶分布").containsAllEntriesOf(BUCKET_COUNTS);
        for (Map.Entry<String, Integer> e : HOLDOUT_COUNTS.entrySet()) {
            assertThat(holdoutCounts.getOrDefault(e.getKey(), 0))
                    .as("holdout 分层: %s", e.getKey()).isEqualTo(e.getValue());
        }
        // 每桶至少 1 条 dev（TOOL_CALLING 全 dev：golden 回归防线须常跑 CI）
        assertThat(holdoutCounts.getOrDefault("TOOL_CALLING", 0)).isZero();
        // 总体比例约 70/30（允许 25%–35%）
        long holdout = holdoutCounts.values().stream().mapToLong(Integer::longValue).sum();
        assertThat(holdout).as("holdout 条数").isEqualTo(13);
        assertThat(holdout * 100.0 / cases.size()).isBetween(25.0, 35.0);
        // 复核状态：RAG/golden 继承 34 条 HUMAN_REVIEWED，business 12 条 PENDING_REVIEW
        assertThat(reviewCounts.get("HUMAN_REVIEWED")).isEqualTo(34);
        assertThat(reviewCounts.get("PENDING_REVIEW")).isEqualTo(12);
    }

    @Test
    void goldenDerivedPayloadsPassExistingV1Loader() throws IOException {
        JsonNode root = loadFrozenSet();
        List<JsonNode> goldenPayloads = new ArrayList<>();
        Set<String> sourceRefs = new HashSet<>();
        for (JsonNode c : root.path("cases")) {
            if ("golden-cases.json".equals(c.path("sourceAsset").asText())) {
                goldenPayloads.add(c.path("payload"));
                sourceRefs.add(c.path("sourceRef").asText());
            }
        }
        assertThat(goldenPayloads).hasSize(20);

        // 关键继承保真断言：20 条 payload 原样拼成 v1 文档，走既有 GoldenCaseLoader 加载路径
        Map<String, Object> v1 = new HashMap<>();
        v1.put("version", 1);
        v1.put("cases", json.convertValue(goldenPayloads, List.class));
        List<GoldenCase> parsed = new GoldenCaseLoader().parse(json.writeValueAsString(v1));
        assertThat(parsed).hasSize(20);
        assertThat(parsed).extracting(GoldenCase::id).doesNotHaveDuplicates();
        assertThat(parsed).extracting(GoldenCase::id).containsExactlyInAnyOrderElementsOf(sourceRefs);
    }

    @Test
    void ragDerivedMarkersRemainVerbatimSubstringsOfFrozenCorpus() throws IOException {
        JsonNode root = loadFrozenSet();

        // 语料文档缓存：filename -> 全文
        Map<String, String> corpus = new HashMap<>();
        Set<String> manifestFiles = new HashSet<>();
        for (JsonNode doc : json.readTree(Files.readString(
                CORPUS_DIR.resolve("corpus-manifest.json"), StandardCharsets.UTF_8)).path("documents")) {
            manifestFiles.add(doc.path("filename").asText());
        }
        assertThat(manifestFiles).hasSize(23);

        int ragCases = 0;
        for (JsonNode c : root.path("cases")) {
            if (!"RAG_QA".equals(c.path("bucket").asText())) {
                continue;
            }
            ragCases++;
            assertThat(c.path("sourceRef").asText())
                    .as("RAG case sourceRef 必须回指 P10 原题 id")
                    .isEqualTo(c.path("payload").path("id").asText());
            // 拒答题不赋予证据；非拒答题 factSources 与 citationTarget 一致
            boolean abstain = c.path("payload").path("abstain").asBoolean(false);
            JsonNode facts = c.path("factSources");
            assertThat(facts.isArray()).isTrue();
            if (abstain) {
                assertThat(facts.size()).as("%s 拒答题不得有事实来源", c.path("case_id").asText()).isZero();
                continue;
            }
            assertThat(facts.size()).as("%s 至少一条可信事实", c.path("case_id").asText()).isGreaterThanOrEqualTo(1);

            List<String> citation = new ArrayList<>();
            for (int i = 0; i < facts.size(); i++) {
                String document = facts.get(i).path("document").asText();
                String marker = facts.get(i).path("marker").asText();
                assertThat(manifestFiles).as("%s 引用文档须在 corpus-manifest 内: %s", c.path("case_id").asText(), document).contains(document);
                String text = corpus.computeIfAbsent(document, this::readCorpusDoc);
                assertThat(text).as("%s 的 marker 必须是 %s 的逐字子串（题目语义零改动）", c.path("case_id").asText(), document).contains(marker);
                if (!citation.contains(document)) {
                    citation.add(document);
                }
            }
            JsonNode citationTarget = c.path("citationTarget");
            assertThat(citationTarget.isArray()).isTrue();
            List<String> target = new ArrayList<>();
            citationTarget.forEach(n -> target.add(n.asText()));
            assertThat(target).as("%s citationTarget 与 factSources 文档一致", c.path("case_id").asText()).isEqualTo(citation);
        }
        assertThat(ragCases).isEqualTo(14);
    }

    @Test
    void businessDerivedTestClassesExist() throws IOException {
        JsonNode root = loadFrozenSet();
        List<String> classNames = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            if (!"business-evaluation-cases.json".equals(c.path("sourceAsset").asText())) {
                continue;
            }
            JsonNode payload = c.path("payload");
            assertThat(payload.path("scenario").asText()).isEqualTo(c.path("question").asText());
            assertThat(payload.path("gate").asText()).isEqualTo(c.path("allowedResultRange").path("description").asText());
            assertThat(payload.path("testClass").asText()).isNotBlank();
            assertThat(payload.path("testMethod").asText()).isNotBlank();
            classNames.add(payload.path("testClass").asText());
        }
        assertThat(classNames).hasSize(12);

        Set<String> existing = new HashSet<>();
        try (Stream<Path> files = Files.walk(BACKEND_TEST_JAVA)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> existing.add(p.getFileName().toString().replace(".java", "")));
        }
        for (String cls : classNames) {
            assertThat(existing).as("business 来源绑定的测试类必须真实存在: %s", cls).contains(cls);
        }
    }

    @Test
    void excludedSourcesAreRecordedWithReasons() throws IOException {
        JsonNode root = loadFrozenSet();
        JsonNode excluded = root.path("excludedSources");
        assertThat(excluded.isArray()).isTrue();
        assertThat(excluded.size()).as("business-eval 17 条素材中排除 5 条").isEqualTo(5);
        Set<String> excludedIds = new HashSet<>();
        for (JsonNode e : excluded) {
            assertThat(e.path("sourceAsset").asText()).isEqualTo("business-evaluation-cases.json");
            assertThat(e.path("reason").asText()).isNotBlank();
            excludedIds.add(e.path("id").asText());
        }
        assertThat(excludedIds).containsExactlyInAnyOrder(
                "object-identity", "insufficient-sources", "multi-sources",
                "task-pages-document-continuation", "upgrade");
        // 排除项不得混入 case 集
        Set<String> caseIds = new HashSet<>();
        root.path("cases").forEach(c -> caseIds.add(c.path("case_id").asText()));
        assertThat(caseIds).doesNotContainAnyElementsOf(excludedIds);
    }

    private String readCorpusDoc(String filename) {
        try {
            return Files.readString(CORPUS_DIR.resolve(filename), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 P9 冻结语料失败: " + filename, e);
        }
    }
}
