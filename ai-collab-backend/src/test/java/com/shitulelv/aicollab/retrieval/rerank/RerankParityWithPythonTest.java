package com.shitulelv.aicollab.retrieval.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.SemanticHeuristicReranker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 跨语言基线一致性：Java {@link SemanticHeuristicReranker} 与 Python {@code ai-eval rerank --scorer heuristic}
 * 在冻结集 {@code rerank_baseline.v1.jsonl} 上必须给出<b>完全相同</b>的 top-8 排序与逐位一致的 NDCG@8。
 *
 * <p><b>期望文件的生成步骤</b>（不要手改，必须由 CLI 生成后整体提交）：</p>
 *
 * <pre>{@code
 * cd ai-collab-ai-worker
 * uv run ai-eval rerank tests/fixtures/rerank_set/rerank_baseline.v1.jsonl \
 *   --scorer heuristic --json > ../ai-collab-backend/src/test/resources/rerank/python-heuristic-eval.json
 * }</pre>
 *
 * <p>Python 复刻的是 {@code SemanticHeuristicReranker} 的打分与排序（见
 * {@code ai-collab-ai-worker/src/ai_collab_worker/eval/rerank.py} 的 {@code heuristic_rank}）：
 * 停用词表、{@code [\p{P}\p{S}\s]+} 切分、中文 bigram、{@code MAX_TERMS=64}、
 * {@code MAX_CHINESE_BIGRAMS=48}、{@code CJK_TOKEN_MAX_LENGTH=10}、虚词端点过滤，
 * {@code 0.35*rrf + 0.40*content + 0.25*heading}，以及
 * "rerankScore 降序 → rrfScore 降序（{@code |Δ|<=1e-9} 视为并列）→ id 升序"的排序。</p>
 *
 * <p><b>id 序同构（重要，不是放宽断言）</b>：两侧并列裁决都按"候选 id 升序"，但 Java 的
 * {@code RetrievalCandidate.id()} 是 {@link UUID}（按 128 位无符号比较），Python 侧是
 * 冻结集里的字符串 id（{@code "c1"…"c10"}，按码点比较）。若直接把 id 哈希成任意 UUID，
 * 并列候选的相对次序就会由哈希决定，两侧必然分叉——这与排序算法无关，只与 id 表示有关
 * （该冻结集 15 条 case 中有 14 条存在分数并列）。因此本测试把同一 case 内的候选 id
 * 按<b>字典序</b>映射到递增 UUID（{@code new UUID(0, rank)}），使两侧的"id 升序"成为同一个
 * 全序；排序结果仍然逐位完全相等，断言没有被放宽。线上不存在该分叉：python 模式下 worker
 * 只回分数，排序仍由 Java 用 UUID 完成。</p>
 */
class RerankParityWithPythonTest {

    /** 冻结集路径：相对 {@code ai-collab-backend/} 模块目录（surefire 的 basedir）。 */
    private static final Path FROZEN_SET = Path.of("..", "ai-collab-ai-worker", "tests", "fixtures",
            "rerank_set", "rerank_baseline.v1.jsonl");
    private static final String EXPECTED_RESOURCE = "/rerank/python-heuristic-eval.json";
    private static final int TOP_K = 8;
    private static final int EXPECTED_CASES = 15;
    private static final double NDCG_TOLERANCE = 1e-9;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<FixtureCase> cases;
    private static Map<String, ExpectedCase> expected;
    private static double expectedAggregateNdcg;

    @BeforeAll
    static void loadCorpus() throws IOException {
        cases = loadFrozenSet();
        JsonNode report = loadExpectedReport();
        expected = parseExpectedCases(report);
        expectedAggregateNdcg = parseAggregateNdcg(report);
    }

    @Test
    void frozenSetAndExpectedReportDescribeTheSameFifteenCases() {
        assertThat(cases).hasSize(EXPECTED_CASES);
        assertThat(cases.stream().map(FixtureCase::id).toList())
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (FixtureCase testCase : cases) {
            List<String> candidateIds = testCase.candidates().stream()
                    .map(FixtureCandidate::id)
                    .toList();
            assertThat(testCase.candidates()).isNotEmpty();
            // labels / supports 必须覆盖全部候选，否则 NDCG@8 会静默漏算。
            assertThat(testCase.labels().keySet()).containsExactlyInAnyOrderElementsOf(candidateIds);
            assertThat(testCase.supports().keySet()).containsExactlyInAnyOrderElementsOf(candidateIds);
        }
    }

    @Test
    void javaTopEightRankingMatchesPythonCaseByCase() {
        for (FixtureCase testCase : cases) {
            List<String> javaRanking = javaRanking(testCase);
            ExpectedCase python = expected.get(testCase.id());
            assertThat(javaRanking)
                    .as("case %s 的 top-%d 排序必须与 Python heuristic 逐位一致", testCase.id(), TOP_K)
                    .containsExactlyElementsOf(python.ranking());
        }
    }

    @Test
    void javaNdcgAtEightMatchesPythonWithinTolerance() {
        for (FixtureCase testCase : cases) {
            ExpectedCase python = expected.get(testCase.id());
            double javaNdcg = ndcgAtK(javaRanking(testCase), testCase.labels(), TOP_K);
            assertThat(Math.abs(javaNdcg - python.ndcgAt8()))
                    .as("case %s 的 NDCG@%d：java=%s python=%s", testCase.id(), TOP_K,
                            javaNdcg, python.ndcgAt8())
                    .isLessThanOrEqualTo(NDCG_TOLERANCE);
        }
    }

    @Test
    void aggregateNdcgAtEightMatchesPythonWithinTolerance() {
        List<FixtureCase> ordered = new ArrayList<>(cases);
        ordered.sort(Comparator.comparing(FixtureCase::id));

        double total = 0.0;
        for (FixtureCase testCase : ordered) {
            total += ndcgAtK(javaRanking(testCase), testCase.labels(), TOP_K);
        }
        double javaAggregate = total / ordered.size();

        assertThat(Math.abs(javaAggregate - expectedAggregateNdcg))
                .as("aggregate ndcgAt8：java=%s python=%s", javaAggregate, expectedAggregateNdcg)
                .isLessThanOrEqualTo(NDCG_TOLERANCE);
    }

    // ---------- Java 基线 ----------

    /**
     * 用真实 {@link SemanticHeuristicReranker} 打分：content=text、heading=title、rrfScore=0.0，
     * 与 Python {@code heuristic_rank(case.query, case.candidates, top_k=8)} 的可比输入一一对应。
     */
    private static List<String> javaRanking(FixtureCase testCase) {
        Map<String, UUID> uuidByCandidateId = alignedUuids(testCase);
        Map<UUID, String> candidateIdByUuid = new LinkedHashMap<>();
        uuidByCandidateId.forEach((candidateId, uuid) -> candidateIdByUuid.put(uuid, candidateId));

        List<RetrievalCandidate> candidates = new ArrayList<>(testCase.candidates().size());
        UUID documentId = UUID.nameUUIDFromBytes(("rerank-baseline:" + testCase.id())
                .getBytes(StandardCharsets.UTF_8));
        for (FixtureCandidate candidate : testCase.candidates()) {
            candidates.add(new RetrievalCandidate(
                    uuidByCandidateId.get(candidate.id()),
                    documentId,
                    "rerank_baseline.v1.jsonl",
                    candidate.title(),
                    candidate.text(),
                    "hash-" + candidate.id(),
                    Map.of(),
                    null,
                    null,
                    null,
                    null,
                    0.0,
                    null,
                    null));
        }

        List<RetrievalCandidate> reranked = new SemanticHeuristicReranker()
                .rerank(testCase.query(), candidates, TOP_K);
        return reranked.stream().map(item -> candidateIdByUuid.get(item.id())).toList();
    }

    /**
     * 把候选 id 按字典序映射到递增 UUID，使 Java 的 {@code UUID.compareTo} 与 Python 的
     * 字符串 {@code <} 表达同一个全序（详见类注释）。
     */
    private static Map<String, UUID> alignedUuids(FixtureCase testCase) {
        List<String> sortedIds = testCase.candidates().stream()
                .map(FixtureCandidate::id)
                .sorted()
                .toList();
        Map<String, UUID> byId = new LinkedHashMap<>();
        for (int index = 0; index < sortedIds.size(); index++) {
            byId.put(sortedIds.get(index), new UUID(0L, index + 1L));
        }
        return byId;
    }

    /** graded NDCG@k：gain {@code 2^rel-1}、discount {@code log2(rank+1)}，IDCG 取该 case 全量标签的理想排序。 */
    private static double ndcgAtK(List<String> ranking, Map<String, Integer> labels, int k) {
        boolean hasPositive = labels.values().stream().anyMatch(relevance -> relevance > 0);
        if (labels.isEmpty() || !hasPositive) {
            // 冻结口径 §4：无正例记 1.0（Python 的 noPositiveLabels 分支）。
            return 1.0;
        }
        double dcg = 0.0;
        int limit = Math.min(k, ranking.size());
        for (int rank = 1; rank <= limit; rank++) {
            dcg += gain(labels.get(ranking.get(rank - 1))) / log2(rank + 1);
        }
        List<Integer> ideal = labels.values().stream()
                .sorted(Comparator.reverseOrder())
                .limit(k)
                .toList();
        double idcg = 0.0;
        for (int rank = 1; rank <= ideal.size(); rank++) {
            idcg += gain(ideal.get(rank - 1)) / log2(rank + 1);
        }
        return idcg == 0.0 ? 1.0 : dcg / idcg;
    }

    private static double gain(int relevance) {
        return Math.pow(2, relevance) - 1.0;
    }

    /** Python 用 math.log2；Java 无 log2，用换底（差异 ~1ulp，远小于 1e-9 容差）。 */
    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    // ---------- 语料读取（缺失即失败，不 skip） ----------

    private static List<FixtureCase> loadFrozenSet() throws IOException {
        Path absolute = FROZEN_SET.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            fail("冻结集不存在：" + absolute + "（应在 ai-collab-ai-worker/tests/fixtures/rerank_set/ 下）");
        }
        List<FixtureCase> parsed = new ArrayList<>();
        for (String line : Files.readAllLines(absolute, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode root = MAPPER.readTree(line);
            Map<String, Integer> labels = new LinkedHashMap<>();
            JsonNode labelsNode = root.get("labels");
            labelsNode.fieldNames().forEachRemaining(field -> labels.put(field, labelsNode.get(field).asInt()));

            Map<String, Boolean> supports = new LinkedHashMap<>();
            JsonNode supportsNode = root.get("supports");
            supportsNode.fieldNames().forEachRemaining(field -> supports.put(field, supportsNode.get(field).asBoolean()));

            List<FixtureCandidate> candidates = new ArrayList<>();
            for (JsonNode candidate : root.get("candidates")) {
                candidates.add(new FixtureCandidate(
                        candidate.get("id").asText(),
                        candidate.get("title").asText(),
                        candidate.get("text").asText()));
            }
            parsed.add(new FixtureCase(
                    root.get("id").asText(),
                    root.get("query").asText(),
                    List.copyOf(candidates),
                    Map.copyOf(labels),
                    Map.copyOf(supports)));
        }
        if (parsed.isEmpty()) {
            fail("冻结集为空：" + absolute);
        }
        return List.copyOf(parsed);
    }

    private static JsonNode loadExpectedReport() throws IOException {
        try (InputStream stream = RerankParityWithPythonTest.class.getResourceAsStream(EXPECTED_RESOURCE)) {
            if (stream == null) {
                fail("期望文件缺失：classpath " + EXPECTED_RESOURCE
                        + "（按类注释里的 ai-eval 命令重新生成并提交）");
                throw new IllegalStateException("unreachable");
            }
            return MAPPER.readTree(stream.readAllBytes());
        }
    }

    private static Map<String, ExpectedCase> parseExpectedCases(JsonNode root) {
        if (root.path("schemaVersion").asInt() != 1) {
            fail("期望文件 schemaVersion 必须为 1");
        }
        if (!"heuristic".equals(root.path("scorer").asText())) {
            fail("期望文件的 scorer 必须是 heuristic，实际 " + root.path("scorer").asText());
        }
        if (root.path("topK").asInt() != TOP_K) {
            fail("期望文件 topK 必须是 " + TOP_K + "，实际 " + root.path("topK").asInt());
        }
        Map<String, ExpectedCase> byId = new TreeMap<>();
        for (JsonNode node : root.path("cases")) {
            List<String> ranking = new ArrayList<>();
            node.path("ranking").forEach(id -> ranking.add(id.asText()));
            byId.put(node.get("id").asText(),
                    new ExpectedCase(node.get("id").asText(), List.copyOf(ranking),
                            node.get("ndcgAt8").asDouble()));
        }
        if (byId.size() != EXPECTED_CASES) {
            fail("期望文件必须包含 " + EXPECTED_CASES + " 条 case，实际 " + byId.size());
        }
        return Map.copyOf(byId);
    }

    private static double parseAggregateNdcg(JsonNode root) {
        JsonNode aggregate = root.path("aggregate");
        if (aggregate.path("cases").asInt() != EXPECTED_CASES) {
            fail("期望文件 aggregate.cases 必须是 " + EXPECTED_CASES);
        }
        JsonNode ndcg = aggregate.get("ndcgAt8");
        if (ndcg == null || !ndcg.isNumber()) {
            fail("期望文件缺少 aggregate.ndcgAt8");
            throw new IllegalStateException("unreachable");
        }
        return ndcg.asDouble();
    }

    // ---------- 数据载体 ----------

    private record FixtureCandidate(String id, String title, String text) {
    }

    private record FixtureCase(String id, String query, List<FixtureCandidate> candidates,
            Map<String, Integer> labels, Map<String, Boolean> supports) {
    }

    private record ExpectedCase(String id, List<String> ranking, double ndcgAt8) {
    }
}
