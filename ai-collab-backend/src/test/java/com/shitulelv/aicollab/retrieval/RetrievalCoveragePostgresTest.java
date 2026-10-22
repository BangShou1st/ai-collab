package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfig;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.retrieval.RetrievalCoverage.ExpectedEvidence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-RAG-01A B 部分：<b>确定性</b>检索覆盖基线（真实 PostgreSQL + pgvector，Testcontainers）。
 *
 * <p>真实的部分：Flyway 迁移后的真实 schema、真实 pgvector 检索与排序、真实指纹与代际过滤、
 * 真实 {@link DocumentSearchService} 与 {@link KnowledgeContextBuilder}。</p>
 *
 * <p>合成的部分：查询与分块向量由 {@link SyntheticEmbedder} 生成（词元哈希桶向量），
 * 所以“哪条证据该排第几”是可推导的，不含模型随机性。
 * <b>换入真实模型后召回头部会完全不同，这里的一切数字都不代表模型语义召回质量，
 * 更不代表模型回答质量。</b>真实模型基线见 {@code RealRetrievalBaselineTest}
 * （{@code AI_REAL_RETRIEVAL} 门控，需要真实 Ollama）。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "agent.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class RetrievalCoveragePostgresTest {
    private static final int TOP_8 = 8;
    private static final int TOP_20 = 20;
    private static final int QA_LIMIT = KnowledgeContextBuilder.DEFAULT_MAX_SOURCES;
    private static final int AGENT_LIMIT = KnowledgeContextBuilder.MAX_SOURCES_LIMIT;
    private static final String PROVIDER = "OLLAMA";
    private static final String MODEL = "synthetic-retrieval-baseline";
    private static final int DIMENSION = SyntheticEmbedder.DIMENSIONS;
    private static final String FINGERPRINT =
            EmbeddingFingerprints.fingerprint(PROVIDER, MODEL, DIMENSION);
    /**
     * 诊断 JSON 的<b>唯一</b>输出位置：Maven 构建目录 {@code target/retrieval-baseline/}。
     *
     * <p>刻意<b>不</b>写回仓库里那份已跟踪的
     * {@code docs/acceptance-evidence/2026-10-16/d-rag-01a/deterministic-baseline.json}：
     * 该文件是一次验收样例（含每次运行都会变化的随机 UUID），
     * 让测试覆盖它会反复污染工作区、并制造“产物字节一致”的假象。
     * {@code target/} 已被 {@code ai-collab-backend/.gitignore} 忽略。</p>
     */
    static final Path EVIDENCE_DIRECTORY = Path.of("target", "retrieval-baseline");
    static final Path EVIDENCE_PATH = EVIDENCE_DIRECTORY.resolve("deterministic-baseline.json");
    static final Path REAL_OLLAMA_EVIDENCE_PATH = EVIDENCE_DIRECTORY.resolve("real-ollama-retrieval.json");

    private record SemanticChunk(int documentIndex, int chunkNo, String heading, String content) {}

    private record SemanticCase(
            String name, String query, List<Integer> documentIndexes, List<Integer> expectedChunkIndexes) {}

    /** 每个用例一条，测试结束后一次性落盘，避免用例执行顺序影响证据内容。 */
    private static final Map<String, Map<String, Object>> EVIDENCE = new LinkedHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @MockitoBean
    ProjectEmbeddingGateway embeddings;
    @MockitoBean
    TaskPlanRecoveryJob taskPlanRecoveryJob;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentSearchService search;

    private final KnowledgeContextBuilder builder = new KnowledgeContextBuilder();

    private UUID user;
    private UUID project;

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE document_chunk, project_document, project_member, project, app_user,
                         embedding_index_generation, system_embedding_config CASCADE
                """);
        user = insertUser();
        project = insertProject();
    }

    /** 仓库中已跟踪的那份验收样例：测试只允许读它，绝不允许写它。 */
    static final Path TRACKED_SNAPSHOT = Path.of("..", "docs", "acceptance-evidence",
            "2026-10-16", "d-rag-01a", "deterministic-baseline.json");

    @Test
    void evidenceOutputGoesToTargetAndNeverRewritesTheTrackedSnapshot() {
        // 回归守卫：诊断输出必须落在 target/ 下，且不得等于仓库里已跟踪的快照路径。
        assertThat(EVIDENCE_PATH.normalize().startsWith(Path.of("target")))
                .as("诊断 JSON 必须输出到 Maven target/ 下")
                .isTrue();
        assertThat(EVIDENCE_PATH.normalize())
                .as("诊断 JSON 不得写回仓库中已跟踪的证据文件")
                .isNotEqualTo(TRACKED_SNAPSHOT.normalize());
        assertThat(EVIDENCE_PATH.normalize().toString())
                .as("诊断 JSON 不得落在 docs/ 证据目录内")
                .doesNotContain("docs");
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Files.createDirectories(EVIDENCE_PATH.getParent());
        // LinkedHashMap 而非 Map.of：证据文件的键顺序也要确定性，便于逐次比对 diff。
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("note", "D-RAG-01A/01B 确定性检索基线的机器可读诊断数据。pgvector 检索、"
                + "指纹/代际过滤、知识上下文构建（默认入口与跨文档入口 buildDiverse）均为真实实现；"
                + "嵌入向量为 SyntheticEmbedder 合成，因此不代表模型召回质量，也不代表模型回答质量。"
                + "损失侧字段 droppedByContext 拆分为 crossDocumentDedupLoss / intraDocumentDedupLoss / "
                + "以及阈值、空正文和未归因类别，互斥计数之和恒等于 droppedByContext。"
                + "本文件由测试写入 Maven target/retrieval-baseline/，不是仓库中那份验收快照。");
        document.put("topKObservations", List.of(TOP_8, TOP_20));
        document.put("agentCandidatePool", 20);
        document.put("knowledgeQaContextLimit", QA_LIMIT);
        document.put("agentContextLimit", AGENT_LIMIT);
        document.put("cases", EVIDENCE);
        Files.writeString(EVIDENCE_PATH,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document),
                StandardCharsets.UTF_8);
        // 交付到控制台，便于 CI 日志直接查看，而不必从构建目录取文件。
        System.out.println("RETRIEVAL_BASELINE_DIAGNOSTICS=" + EVIDENCE_PATH.toAbsolutePath());
    }

    // ---- 1. 单事实 ----

    @Test
    void singleFactIsRecalledAndRetained() {
        UUID document = insertDocument("需求规格.md");
        UUID evidence = insertChunk(document, 0, "容量",
                "system must support at most twenty concurrent exports per tenant");
        insertUnrelatedNoise(document, 1, 6);

        var measured = runCase("单事实", TOP_8, "how many concurrent exports per tenant",
                List.of(document), null, List.of(new ExpectedEvidence(document, evidence, "并发导出上限")));

        assertThat(measured.get("retrievalRecall")).isEqualTo(1.0d);
        assertThat(measured.get("contextEvidenceRetention")).isEqualTo(1.0d);
        assertThat(measured.get("droppedByContext")).isEqualTo(0);
    }

    // ---- 2. 跨文档多事实 ----

    @Test
    void crossDocumentFactsAreAllRecalledAndCovered() {
        List<UUID> documents = new ArrayList<>();
        List<ExpectedEvidence> expected = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            UUID document = insertDocument("分册" + i + ".md");
            documents.add(document);
            UUID evidence = insertChunk(document, 0, "事实" + i,
                    "fact marker alpha" + i + " records acceptance obligation number " + i);
            expected.add(new ExpectedEvidence(document, evidence, "分册" + i + "事实"));
            insertUnrelatedNoise(document, 1, 3);
        }

        var measured = runCase("跨文档多事实", TOP_8,
                "acceptance obligation fact marker alpha1 alpha2 alpha3",
                documents, null, expected);

        assertThat(measured.get("retrievalRecall")).isEqualTo(1.0d);
        assertThat(measured.get("contextEvidenceRetention")).isEqualTo(1.0d);
        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) measured.get("documentCoverage");
        assertThat(coverage.get("candidateRatio")).isEqualTo(1.0d);
        assertThat(coverage.get("retainedRatio")).isEqualTo(1.0d);
    }

    // ---- 3. 精确编号 ----

    @Test
    void exactIdentifierRanksFirst() {
        UUID document = insertDocument("变更单.md");
        UUID evidence = insertChunk(document, 0, "变更",
                "change request REQ-7741 authorizes the schema freeze window");
        insertUnrelatedNoise(document, 1, 8);

        var measured = runCase("精确编号", TOP_8, "REQ-7741",
                List.of(document), null, List.of(new ExpectedEvidence(document, evidence, "REQ-7741")));

        assertThat(measured.get("retrievalRecall")).isEqualTo(1.0d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> described = (List<Map<String, Object>>) measured.get("expectedEvidence");
        assertThat(described.getFirst().get("candidateRank")).isEqualTo(1);
    }

    // ---- 4. 同内容不同文档 ----

    @Test
    void identicalContentAcrossDocumentsIsRecalledAndKeptAsDistinctSources() {
        UUID first = insertDocument("原始件.md");
        UUID second = insertDocument("复印件.md");
        UUID firstChunk = insertChunk(first, 0, "条款",
                "shared clause text duplicated across documents");
        UUID secondChunk = insertChunk(second, 0, "条款",
                "shared clause text duplicated across documents");

        var measured = runCase("同内容不同文档", TOP_8,
                "shared clause text duplicated across documents",
                List.of(first, second), null,
                List.of(new ExpectedEvidence(first, firstChunk, "原始件条款"),
                        new ExpectedEvidence(second, secondChunk, "复印件条款")));

        // 两条都进了向量候选，跨文档入口按“文档感知去重”保留两份独立的引用身份：
        // 内容相同但 documentId/chunkId 各自独立，不再被全局 contentHash 合并成一条。
        assertThat(measured.get("retrievalRecall")).isEqualTo(1.0d);
        assertThat(measured.get("contextEvidenceRetention")).isEqualTo(1.0d);
        assertThat(measured.get("droppedByContext")).isEqualTo(0);
        assertThat(measured.get("crossDocumentDedupLoss")).isEqualTo(0);
        assertThat(measured.get("retainedSourceCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> retained = (List<Map<String, Object>>) measured.get("retainedSources");
        assertThat(retained).extracting(item -> item.get("documentId"))
                .containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(retained).extracting(item -> item.get("chunkId"))
                .containsExactlyInAnyOrder(firstChunk.toString(), secondChunk.toString());
    }

    @Test
    void defaultEntryPreservesCrossDocumentIdentityOnRealPgvector() {
        UUID first = insertDocument("原始件.md");
        UUID second = insertDocument("复印件.md");
        UUID firstChunk = insertChunk(first, 0, "条款",
                "shared clause text duplicated across documents");
        UUID secondChunk = insertChunk(second, 0, "条款",
                "shared clause text duplicated across documents");
        List<DocumentSearchHit> candidates = runSearch(
                "shared clause text duplicated across documents", null,
                List.of(first, second), TOP_8);

        // 默认入口（普通知识库问答）：本次修复后不再按全局 contentHash 丢弃另一份文档的引用身份。
        var built = builder.buildWithDiagnostics(candidates, AGENT_LIMIT);
        var measured = RetrievalCoverage.measure(TOP_8, AGENT_LIMIT,
                List.of(new ExpectedEvidence(first, firstChunk, "原始件条款"),
                        new ExpectedEvidence(second, secondChunk, "复印件条款")),
                candidates, built);
        record("同内容不同文档(默认入口)", measured);

        assertThat(measured.get("contextEvidenceRetention")).isEqualTo(1.0d);
        assertThat(measured.get("crossDocumentDedupLoss")).isEqualTo(0);
        assertThat(measured.get("droppedByContext")).isEqualTo(0);
    }

    // ---- 5. 无有效证据 ----

    @Test
    void unrelatedQueryProducesNoRetainedContextAndIsUndefinedNotFailed() {
        UUID document = insertDocument("无关件.md");
        insertChunk(document, 0, "无关", "quarterly catering menu rotation schedule");
        insertChunk(document, 1, "无关", "annual cafeteria seating arrangement plan");

        List<DocumentSearchHit> candidates = runSearch(
                "krypton xenon argon spectroscopy", null, List.of(document), TOP_8);
        var context = builder.buildDiverseWithDiagnostics(candidates, AGENT_LIMIT);
        // 语料里本来就没有可回答该问题的证据，所以期望证据集合为空——
        // 不能凭空造一条“期望证据”把未定义的 0/0 伪装成检索失败 0.0。
        var measured = RetrievalCoverage.measure(TOP_8, AGENT_LIMIT, List.of(), candidates, context);

        // pgvector 仍会按相似度返回若干行，但没有一条达到相似度下限：
        // 于是上下文为空，Recall/Retention 未定义（不是 0，也不是失败）。
        assertThat(context.context().sources()).isEmpty();
        assertThat(measured.get("retrievalStatus"))
                .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
        assertThat(measured.get("retrievalRecall")).isNull();
        assertThat(measured.get("contextEvidenceRetention")).isNull();
        assertThat(measured.get("expectedEvidenceCount")).isEqualTo(0);
        assertThat(measured.get("retainedSourceCount")).isEqualTo(0);
        assertThat(RetrievalCoverage.classify("无有效证据", measured, measured).get("verdict"))
                .isEqualTo(RetrievalCoverage.UNDEFINED_NO_EXPECTED_EVIDENCE);
        record("无有效证据", measured);
    }

    // ---- 6. 指定文档范围 ----

    @Test
    void documentScopeExcludesEvidenceFromOtherDocuments() {
        UUID inside = insertDocument("范围内.md");
        UUID outside = insertDocument("范围外.md");
        UUID insideEvidence = insertChunk(inside, 0, "范围",
                "scoped evidence token sigma is only inside the selected document");
        UUID outsideEvidence = insertChunk(outside, 0, "范围",
                "scoped evidence token sigma also appears in the other document");

        var scoped = runCase("指定文档范围", TOP_8, "scoped evidence token sigma",
                List.of(inside), List.of(inside),
                List.of(new ExpectedEvidence(inside, insideEvidence, "范围内证据")));

        assertThat(scoped.get("retrievalRecall")).isEqualTo(1.0d);
        assertThat(scoped.get("contextEvidenceRetention")).isEqualTo(1.0d);
        assertThat(scoped.get("candidateCount")).isEqualTo(1);

        // 反向对照：同一查询放开范围后，范围外证据可见——证明生效的是“范围”而不是数据缺失。
        List<DocumentSearchHit> unscoped = runSearch("scoped evidence token sigma", null,
                List.of(inside, outside), TOP_20);
        assertThat(unscoped).extracting(DocumentSearchHit::id)
                .contains(insideEvidence, outsideEvidence);
    }

    // ---- 7. 索引代际隔离 ----

    @Test
    void retiredGenerationChunksAreNeverSearched() {
        UUID config = insertEmbeddingConfig();
        UUID retired = insertGeneration(config, "RETIRED");
        UUID active = insertGeneration(config, "ACTIVE");

        UUID document = insertDocument("代际.md");
        UUID oldChunk = insertChunk(document, 0, "旧代",
                "generation marker omega export limit", retired);
        UUID newChunk = insertChunk(document, 1, "新代",
                "generation marker omega export limit", active);

        List<DocumentSearchHit> candidates = runSearch(
                "generation marker omega export limit", active, List.of(document), TOP_20);

        assertThat(candidates).extracting(DocumentSearchHit::id).containsExactly(newChunk);
        assertThat(candidates).extracting(DocumentSearchHit::id).doesNotContain(oldChunk);

        var measured = RetrievalCoverage.measure(TOP_20, AGENT_LIMIT,
                List.of(new ExpectedEvidence(document, newChunk, "活跃代际证据")),
                candidates, builder.build(candidates, AGENT_LIMIT));
        assertThat(measured.get("retrievalRecall")).isEqualTo(1.0d);
        record("索引代际隔离", measured);

        // 反向对照：不带代际（NULL）检索看不到任何带代际的 chunk——隔离是双向的。
        assertThat(runSearch("generation marker omega export limit", null, List.of(document), TOP_20))
                .isEmpty();
    }

    // ---- 8. Top 8 / Top 20 归因：数据库没召回 vs 上下文丢弃 ----

    @Test
    void top8VersusTop20AttributionIsObserved() {
        UUID document = insertDocument("深排位.md");
        String query = "rare needle zeta";
        // 12 条高度相关的噪声把正确证据挤出 Top 8；正确证据本身被大量无关词稀释，相似度较低。
        for (int i = 0; i < 12; i++) {
            insertChunk(document, i, "噪声", "rare needle zeta noise variant " + i);
        }
        String diluted = "rare needle zeta " + IntStream.range(0, 60)
                .mapToObj(i -> "placeholder" + i)
                .collect(Collectors.joining(" "));
        UUID evidence = insertChunk(document, 12, "深处", diluted);

        var top8 = runCase("深排位(Top8)", TOP_8, query, List.of(document), null,
                List.of(new ExpectedEvidence(document, evidence, "深排位证据")));
        var top20 = runCase("深排位(Top20)", TOP_20, query, List.of(document), null,
                List.of(new ExpectedEvidence(document, evidence, "深排位证据")));

        assertThat(top8.get("retrievalRecall")).isEqualTo(0.0d);
        assertThat(top20.get("retrievalRecall")).isEqualTo(1.0d);

        var classification = RetrievalCoverage.classify("深排位", top8, top20);
        // Top 8 没有、Top 20 有：属于候选窗口截断，不是数据库没召回。
        assertThat(classification.get("verdict")).isEqualTo("WINDOW_TRUNCATED");
        EVIDENCE.put("深排位(归因)", classification);
    }

    // ---- 9. 上下文条数上限：知识问答 5 vs Agent 12（正文预算不变） ----

    @Test
    void contextSourceCapDiffersBetweenKnowledgeQaAndAgent() {
        UUID document = insertDocument("上限.md");
        List<ExpectedEvidence> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID chunk = insertChunk(document, i, "事实" + i,
                    "cap probe evidence item " + i + " unique token evidence" + i);
            expected.add(new ExpectedEvidence(document, chunk, "事实" + i));
        }
        String query = "cap probe evidence unique token " + IntStream.range(0, 12)
                .mapToObj(i -> "evidence" + i).collect(Collectors.joining(" "));

        List<DocumentSearchHit> candidates = runSearch(query, null, List.of(document), TOP_20);
        var qaContext = builder.buildWithDiagnostics(candidates, QA_LIMIT);
        var agentContext = builder.buildDiverseWithDiagnostics(candidates, AGENT_LIMIT);

        var qa = RetrievalCoverage.measure(TOP_20, QA_LIMIT, expected, candidates, qaContext);
        var agent = RetrievalCoverage.measure(TOP_20, AGENT_LIMIT, expected, candidates, agentContext);
        record("来源上限-知识问答", qa);
        record("来源上限-Agent", agent);

        assertThat(qaContext.context().sources().size()).isEqualTo(5);
        assertThat(agentContext.context().sources().size()).isEqualTo(12);
        assertThat((int) agent.get("droppedByContext")).isLessThan((int) qa.get("droppedByContext"));
    }

    @Test
    void raisingSourceCapDoesNotBypassTheBodyBudget() {
        UUID document = insertDocument("预算.md");
        List<ExpectedEvidence> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // 每条约 3000 字符：12 条远超 8000 字符预算。
            String content = "budget probe item " + i + " " + "padding".repeat(400);
            UUID chunk = insertChunk(document, i, "事实" + i, content);
            expected.add(new ExpectedEvidence(document, chunk, "事实" + i));
        }
        String query = "budget probe item " + IntStream.range(0, 12)
                .mapToObj(i -> "pba" + i).collect(Collectors.joining(" "));

        List<DocumentSearchHit> candidates = runSearch(query, null, List.of(document), TOP_20);

        // 默认入口：先到先得，预算耗尽即停止追加——允许 12 个来源不等于能塞进 12 条长正文。
        var legacyResult = builder.buildWithDiagnostics(candidates, AGENT_LIMIT);
        KnowledgeContext legacy = legacyResult.context();
        assertThat(legacy.sources().size()).isLessThan(AGENT_LIMIT);
        assertThat(promptCodePoints(legacy)).isLessThanOrEqualTo(8000);

        // 跨文档入口：公平分配仍受同一个 8000 字符硬上限约束，但每条来源都分到可见份额，
        // 因此不再出现“一条长正文吃光预算、其余来源消失”。
        var agentContext = builder.buildDiverseWithDiagnostics(candidates, AGENT_LIMIT);
        var measured = RetrievalCoverage.measure(TOP_20, AGENT_LIMIT, expected, candidates, agentContext);
        record("预算", measured);

        assertThat(promptCodePoints(agentContext.context())).isLessThanOrEqualTo(8000);
        assertThat(agentContext.context().sources().size()).isLessThanOrEqualTo(AGENT_LIMIT);
        assertThat(agentContext.context().sources().size()).isGreaterThan(legacy.sources().size());
        assertThat((int) measured.get("budgetDrop")).isLessThan(
                (int) RetrievalCoverage.measure(TOP_20, AGENT_LIMIT, expected, candidates, legacyResult)
                        .get("budgetDrop"));
    }

    // ---- 11. 跨文档多样性：单一高分文档吃满窗口时，次要文档的有效证据仍要保留 ----

    @Test
    void crossDocumentDiversityRecoversEvidenceBeyondTheEightWindow() {
        UUID primary = insertDocument("主文档.md");
        UUID secondary = insertDocument("次文档.md");
        String query = "cross document coverage marker";
        // 主文档 12 条高分候选把 Top8 占满，次文档的证据相似度更低、排在第 13 位（Top8 之外、Top20 之内）。
        for (int i = 0; i < 12; i++) {
            insertChunk(primary, i, "高频", "cross document coverage marker variant " + i);
        }
        UUID evidence = insertChunk(secondary, 0, "关键结论",
                "cross document coverage marker "
                        + "secondary evidence diluted with many filler tokens beyond the window");
        List<ExpectedEvidence> expected =
                List.of(new ExpectedEvidence(secondary, evidence, "次文档证据"));

        // 旧 Agent 管线（候选窗口 = limit = 8）：次文档证据根本没进候选。
        var legacyCandidates = runSearch(query, null, List.of(primary, secondary), TOP_8);
        var legacy = RetrievalCoverage.measure(TOP_8, TOP_8, expected, legacyCandidates,
                builder.buildDiverseWithDiagnostics(legacyCandidates, TOP_8));
        record("跨文档多样性(旧窗口)", legacy);
        assertThat(legacy.get("notRecalledInTopK")).isEqualTo(1);

        // 新 Agent 管线：有界候选池 20 + 跨文档选择，limit 仍为 8 —— 次文档证据进入上下文。
        var currentCandidates = runSearch(query, null, List.of(primary, secondary), 20);
        var current = RetrievalCoverage.measure(20, TOP_8, expected, currentCandidates,
                builder.buildDiverseWithDiagnostics(currentCandidates, TOP_8));
        record("跨文档多样性", current);

        assertThat(current.get("retrievalRecall")).isEqualTo(1.0d);
        assertThat(current.get("contextEvidenceRetention")).isEqualTo(1.0d);
        assertThat(current.get("droppedByContext")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) current.get("documentCoverage");
        assertThat(coverage.get("candidateRatio")).isEqualTo(1.0d);
        assertThat(coverage.get("retainedRatio")).isEqualTo(1.0d);
    }

    // ---- 12. 跨文档预算：一条超长正文不再挤掉其他文档的短证据 ----

    @Test
    void crossDocumentBudgetSharingKeepsShortEvidenceFromOtherDocument() {
        UUID primary = insertDocument("长文文档.md");
        UUID secondary = insertDocument("短文文档.md");
        String query = "fair budget cross document marker";
        // 长文正文用重复的查询词元堆长（保持高分、约 9600 字符），短证据相似度略低但有效。
        insertChunk(primary, 0, "长文",
                "fair budget cross document marker ".repeat(300));
        UUID evidence = insertChunk(secondary, 0, "短证据",
                "fair budget cross document marker short evidence");
        List<ExpectedEvidence> expected =
                List.of(new ExpectedEvidence(secondary, evidence, "次文档短证据"));

        List<DocumentSearchHit> candidates = runSearch(query, null, List.of(primary, secondary), TOP_8);

        // 旧入口：超长正文先到先得吃光预算，其他文档的短证据消失——预算丢弃。
        var legacy = RetrievalCoverage.measure(TOP_8, AGENT_LIMIT, expected, candidates,
                builder.buildWithDiagnostics(candidates, AGENT_LIMIT));
        record("跨文档预算(旧入口)", legacy);
        assertThat(legacy.get("budgetDrop")).isEqualTo(1);

        // 跨文档入口：公平分配后短证据保留。
        var current = RetrievalCoverage.measure(TOP_8, AGENT_LIMIT, expected, candidates,
                builder.buildDiverseWithDiagnostics(candidates, AGENT_LIMIT));
        record("跨文档预算", current);
        assertThat(current.get("contextEvidenceRetention")).isEqualTo(1.0d);
        assertThat(current.get("budgetDrop")).isEqualTo(0);
        assertThat(RetrievalCoverage.classify("跨文档预算", legacy, legacy)
                .get("retentionVerdict")).isEqualTo("BUDGET_DROP");
    }

    // ---- 13. Top20 仍缺证据：必须归因为数据库没召回，不能错算成上下文丢弃 ----

    @Test
    void evidenceMissingEvenAtTop20IsDatabaseMissNotContextDrop() {
        UUID document = insertDocument("深埋.md");
        String query = "rare needle omega";
        for (int i = 0; i < 26; i++) {
            insertChunk(document, i, "噪声", "rare needle omega filler variant " + i);
        }
        String diluted = "rare needle omega " + IntStream.range(0, 80)
                .mapToObj(i -> "vacuous" + i).collect(Collectors.joining(" "));
        UUID evidence = insertChunk(document, 40, "深处", diluted);

        List<DocumentSearchHit> candidates = runSearch(query, null, List.of(document), TOP_20);
        var measured = RetrievalCoverage.measure(TOP_20, AGENT_LIMIT,
                List.of(new ExpectedEvidence(document, evidence, "深埋证据")),
                candidates, builder.buildDiverse(candidates, AGENT_LIMIT));
        record("Top20 仍缺证据", measured);

        assertThat(measured.get("retrievalRecall")).isEqualTo(0.0d);
        assertThat(measured.get("notRecalledInTopK")).isEqualTo(1);
        // 候选里根本没有这条证据：既不是来源上限、也不是预算、更不是跨文档去重。
        assertThat(measured.get("droppedByContext")).isEqualTo(0);
        assertThat(measured.get("budgetDrop")).isEqualTo(0);
        assertThat(measured.get("sourceCapDrop")).isEqualTo(0);
        var classification = RetrievalCoverage.classify("Top20 仍缺证据", measured, measured);
        assertThat(classification.get("verdict")).isEqualTo("DB_MISS");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AI_REAL_OLLAMA_RETRIEVAL", matches = "true")
    void realOllamaChineseSemanticRetrievalIsMeasuredSeparatelyFromSyntheticBaseline() throws Exception {
        String model = System.getenv().getOrDefault("AI_OLLAMA_SMOKE_MODEL", "qwen3-embedding:0.6b");
        OffsetDateTime now = OffsetDateTime.now();
        String probeFingerprint = EmbeddingFingerprints.fingerprint(PROVIDER, model, 0);
        ProjectEmbeddingGateway probeGateway = ollamaGateway(model, 0, probeFingerprint, null, now);
        long probeStarted = System.nanoTime();
        EmbeddingBatch probe = probeGateway.embed(project, List.of("本地向量维度检查"),
                EmbeddingProgressListener.NONE);
        long probeLatencyMs = elapsedMillis(probeStarted);
        int dimension = probe.dimension();

        String fingerprint = EmbeddingFingerprints.fingerprint(PROVIDER, model, dimension);
        UUID generationId = UUID.randomUUID();
        ProjectEmbeddingGateway gateway = ollamaGateway(model, dimension, fingerprint, generationId, now);
        List<String> documentNames = List.of(
                "上线验收规程.md", "发布回滚预案.md", "任务预算规则.md", "批量导出规范.md",
                "租户并发限制.md", "合同模板甲.md", "合同模板乙.md", "需求变更单.md");
        List<SemanticChunk> corpus = List.of(
                new SemanticChunk(0, 0, "上线门禁",
                        "生产发布前必须完成核心业务流程自动化验收，所有阻断级缺陷关闭，并核对数据迁移结果。"),
                new SemanticChunk(0, 1, "验收记录",
                        "验收记录须包含执行人、执行时间、测试版本和未关闭问题清单。"),
                new SemanticChunk(1, 0, "回滚触发条件",
                        "上线后核心业务指标连续两次低于约定阈值时，立即暂停发布并回滚到最近稳定版本。"),
                new SemanticChunk(1, 1, "回滚复核",
                        "回滚完成后复核数据完整性，并通知项目负责人和当班值守人员。"),
                new SemanticChunk(2, 0, "预算规则",
                        "任务启动前必须确认预算上限；任务运行期间禁止修改预算上限或临时增加可用额度。"),
                new SemanticChunk(2, 1, "超限处理",
                        "实际消耗达到授权预算上限后，任务应暂停并等待负责人批准，不得自动超支。"),
                new SemanticChunk(3, 0, "导出队列",
                        "批量导出由后台队列异步处理，用户可以查看排队、运行和完成状态。"),
                new SemanticChunk(3, 1, "导出格式",
                        "批量导出支持 CSV 与 XLSX，生成文件后提供限时下载链接。"),
                new SemanticChunk(3, 2, "失败重试",
                        "导出任务失败后可以重新提交，系统保留失败原因和最近一次运行时间。"),
                new SemanticChunk(3, 3, "数据范围",
                        "导出文件只包含请求用户有权访问的项目数据，敏感字段按权限脱敏。"),
                new SemanticChunk(3, 4, "导出审计",
                        "每次导出都记录发起人、筛选条件、文件大小和下载时间。"),
                new SemanticChunk(3, 5, "文件保留",
                        "已完成的导出文件在临时存储区保存二十四小时，过期后自动清理。"),
                new SemanticChunk(4, 0, "租户并发上限",
                        "每个租户同一时刻最多运行十二个数据导出任务，超出上限的任务进入等待队列。"),
                new SemanticChunk(5, 0, "重大变更审批",
                        "重大变更须经项目负责人书面批准后方可实施。"),
                new SemanticChunk(6, 0, "重大变更审批",
                        "重大变更须经项目负责人书面批准后方可实施。"),
                new SemanticChunk(7, 0, "REQ-7741",
                        "变更申请 REQ-7741 授权在数据库结构冻结窗口结束后执行索引调整，并要求先完成回滚演练。"));
        List<SemanticCase> cases = List.of(
                new SemanticCase("上线验收与回退条件",
                        "正式发布前的上线验收需要满足哪些条件？如果上线指标不达标，应如何处理？",
                        List.of(0, 1), List.of(0, 2)),
                new SemanticCase("执行中的预算变更限制",
                        "任务已经运行后，可以临时提高额度或调整预算吗？",
                        List.of(2), List.of(4)),
                new SemanticCase("密集导出资料中的租户并发事实",
                        "一个租户同一时间最多可以并行执行多少个数据导出？",
                        List.of(3, 4), List.of(12)),
                new SemanticCase("相同审批条款的来源身份",
                        "合同资料中的重大变更审批要求是什么？",
                        List.of(5, 6), List.of(13, 14)),
                new SemanticCase("同义描述与精确变更编号",
                        "REQ-7741 允许在哪个数据库冻结阶段之后调整索引？",
                        List.of(7), List.of(15)),
                new SemanticCase("资料不足：审计留存年限",
                        "系统规定的审计日志必须保留多少年？",
                        List.of(0, 1, 2, 3, 4, 5, 6, 7), List.of()));

        List<String> allInputs = new ArrayList<>(corpus.stream().map(SemanticChunk::content).toList());
        allInputs.addAll(cases.stream().map(SemanticCase::query).toList());
        long embeddingStarted = System.nanoTime();
        EmbeddingBatch allVectors = gateway.embed(project, allInputs, EmbeddingProgressListener.NONE);
        long embeddingLatencyMs = elapsedMillis(embeddingStarted);
        assertThat(allVectors.provider()).isEqualTo(PROVIDER);
        assertThat(allVectors.model()).isEqualTo(model);
        assertThat(allVectors.dimension()).isEqualTo(dimension);
        assertThat(allVectors.fingerprint()).isEqualTo(fingerprint);
        assertThat(allVectors.generationId()).isEqualTo(generationId);

        insertOllamaGeneration(model, dimension, fingerprint, generationId);
        Map<Integer, UUID> documentIds = new LinkedHashMap<>();
        for (int i = 0; i < documentNames.size(); i++) {
            documentIds.put(i, insertOllamaDocument(documentNames.get(i), model, dimension, fingerprint));
        }
        Map<Integer, UUID> chunkIds = new LinkedHashMap<>();
        for (int index = 0; index < corpus.size(); index++) {
            SemanticChunk chunk = corpus.get(index);
            UUID chunkId = insertOllamaChunk(documentIds.get(chunk.documentIndex()), chunk.chunkNo(),
                    chunk.heading(), chunk.content(), model, dimension, fingerprint, generationId,
                    allVectors.vectors().get(index));
            chunkIds.put(index, chunkId);
        }

        Map<String, List<Double>> queryVectors = new LinkedHashMap<>();
        for (int index = 0; index < cases.size(); index++) {
            queryVectors.put(cases.get(index).query(), allVectors.vectors().get(corpus.size() + index));
        }
        when(embeddings.embed(any(), anyList(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> input = (List<String>) invocation.getArgument(1);
            List<List<Double>> vectors = input.stream().map(queryVectors::get).toList();
            return new EmbeddingBatch(PROVIDER, model, dimension, vectors, fingerprint, generationId);
        });

        List<Map<String, Object>> measuredCases = new ArrayList<>();
        for (SemanticCase semanticCase : cases) {
            List<UUID> scopedDocuments = semanticCase.documentIndexes().stream().map(documentIds::get).toList();
            List<ExpectedEvidence> expected = semanticCase.expectedChunkIndexes().stream()
                    .map(index -> new ExpectedEvidence(
                            documentIds.get(corpus.get(index).documentIndex()), chunkIds.get(index),
                            corpus.get(index).heading()))
                    .toList();

            long top8Started = System.nanoTime();
            List<DocumentSearchHit> top8 = search.search(project, semanticCase.query(), scopedDocuments, TOP_8);
            long top8LatencyMs = elapsedMillis(top8Started);
            long top20Started = System.nanoTime();
            List<DocumentSearchHit> top20 = search.search(project, semanticCase.query(), scopedDocuments, TOP_20);
            long top20LatencyMs = elapsedMillis(top20Started);

            var oldResult = builder.buildWithDiagnostics(top8, TOP_8);
            var newResult = builder.buildDiverseWithDiagnostics(top20, TOP_8);
            Map<String, Object> oldMeasured = RetrievalCoverage.measure(TOP_8, TOP_8,
                    expected, top8, oldResult);
            Map<String, Object> newMeasured = RetrievalCoverage.measure(TOP_20, TOP_8,
                    expected, top20, newResult);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("case", semanticCase.name());
            item.put("query", semanticCase.query());
            item.put("expectedEvidence", expected.stream().map(evidence -> Map.of(
                    "label", evidence.label(), "documentId", evidence.documentId().toString(),
                    "chunkId", evidence.chunkId().toString())).toList());
            item.put("retrievalLatencyMsTop8", top8LatencyMs);
            item.put("retrievalLatencyMsTop20", top20LatencyMs);
            item.put("recallAt8", oldMeasured.get("retrievalRecall"));
            item.put("recallAt20", newMeasured.get("retrievalRecall"));
            item.put("oldStrategy", summarizeRealMeasure(oldMeasured, oldResult.context()));
            item.put("newStrategy", summarizeRealMeasure(newMeasured, newResult.context()));
            item.put("retentionClassification", RetrievalCoverage.classify(
                    semanticCase.name(), oldMeasured, newMeasured));
            measuredCases.add(item);
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evaluation", "real local Ollama embeddings with disposable Testcontainers pgvector database");
        evidence.put("model", model);
        evidence.put("dimension", dimension);
        evidence.put("fingerprint", fingerprint);
        evidence.put("generationId", generationId.toString());
        evidence.put("probeLatencyMs", probeLatencyMs);
        evidence.put("corpusAndQueryEmbeddingLatencyMs", embeddingLatencyMs);
        evidence.put("indexingChunkCount", corpus.size());
        evidence.put("caseCount", measuredCases.size());
        evidence.put("cases", measuredCases);
        Files.createDirectories(REAL_OLLAMA_EVIDENCE_PATH.getParent());
        Files.writeString(REAL_OLLAMA_EVIDENCE_PATH,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence), StandardCharsets.UTF_8);
        System.out.println("REAL_OLLAMA_RETRIEVAL_EVIDENCE=" + REAL_OLLAMA_EVIDENCE_PATH.toAbsolutePath());
        assertThat(measuredCases).hasSize(6);
    }

    private static Map<String, Object> summarizeRealMeasure(
            Map<String, Object> measured, KnowledgeContext context) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("retrievalRecall", measured.get("retrievalRecall"));
        summary.put("contextEvidenceRetention", measured.get("contextEvidenceRetention"));
        summary.put("documentCoverage", measured.get("documentCoverage"));
        summary.put("candidateCount", measured.get("candidateCount"));
        summary.put("droppedByContext", measured.get("droppedByContext"));
        summary.put("dropReasons", Map.of(
                "crossDocumentDedupLoss", measured.get("crossDocumentDedupLoss"),
                "intraDocumentDedupLoss", measured.get("intraDocumentDedupLoss"),
                "sourceCapDrop", measured.get("sourceCapDrop"),
                "budgetDrop", measured.get("budgetDrop"),
                "belowSimilarityThresholdDrop", measured.get("belowSimilarityThresholdDrop"),
                "emptyBodyDrop", measured.get("emptyBodyDrop"),
                "unattributedDrop", measured.get("unattributedDrop")));
        summary.put("retainedSourceCount", context.sources().size());
        summary.put("contextPromptCodePoints", promptCodePoints(context));
        summary.put("retainedSources", measured.get("retainedSources"));
        return summary;
    }

    private static ProjectEmbeddingGateway ollamaGateway(
            String model, int dimension, String fingerprint, UUID generationId, OffsetDateTime now) {
        SystemEmbeddingConfigRepository configRepository = mock(SystemEmbeddingConfigRepository.class);
        SystemEmbeddingConfig config = new SystemEmbeddingConfig(
                UUID.randomUUID(), PROVIDER, "http://127.0.0.1:11434", "/v1/embeddings", null,
                model, dimension, 32, fingerprint, true, now, now, generationId);
        when(configRepository.findActive()).thenReturn(Optional.of(config));
        return new ProjectEmbeddingGateway(configRepository, mock(ModelSecretCipher.class));
    }

    private UUID insertOllamaGeneration(
            String model, int dimension, String fingerprint, UUID generationId) {
        UUID configId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,
                  batch_size,fingerprint,enabled)
                VALUES (?,'OLLAMA','http://127.0.0.1:11434','/v1/embeddings',?,?,32,?,FALSE)
                """, configId, model, dimension, fingerprint);
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'RETIRED')",
                generationId, configId);
        jdbc.update("UPDATE system_embedding_config SET generation_id=? WHERE id=?", generationId, configId);
        return configId;
    }

    private UUID insertOllamaDocument(String filename, String model, int dimension, String fingerprint) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,status,chunk_count,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,uploaded_by,indexed_at)
                VALUES (?,?,?,?,'text/markdown',100,?,'READY',0,'OLLAMA',?,?,?, ?,now())
                """, id, project, filename, filename, "projects/" + project + "/" + id + ".md",
                model, dimension, fingerprint, user);
        return id;
    }

    private UUID insertOllamaChunk(
            UUID document,
            int chunkNo,
            String heading,
            String content,
            String model,
            int dimension,
            String fingerprint,
            UUID generationId,
            List<Double> vector) throws Exception {
        UUID id = UUID.randomUUID();
        String metadata = JSON.writeValueAsString(Map.of("embeddingGeneration", generationId.toString()));
        jdbc.update("""
                INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,
                  content_hash,token_estimate,metadata,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,embedding,generation_id)
                VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,CAST(? AS vector),?)
                """, id, project, document, chunkNo, heading, content, sha256(content),
                Math.max(1, content.codePointCount(0, content.length()) / 4), metadata,
                PROVIDER, model, dimension, fingerprint, vector.toString(), generationId);
        return id;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static int promptCodePoints(KnowledgeContext context) {
        return context.promptSources().codePointCount(0, context.promptSources().length());
    }

    // ---- 执行与记录 ----

    private Map<String, Object> runCase(
            String caseName, int topK, String query, List<UUID> scopedDocuments,
            List<UUID> requestedDocuments, List<ExpectedEvidence> expected) {
        List<UUID> requested = requestedDocuments == null ? scopedDocuments : requestedDocuments;
        List<DocumentSearchHit> candidates = runSearch(query, null, requested, topK);
        // D-RAG-01B：Agent 生产路径（search_project_knowledge）走跨文档入口 buildDiverse。
        var context = builder.buildDiverseWithDiagnostics(candidates, AGENT_LIMIT);
        Map<String, Object> measured = RetrievalCoverage.measure(
                topK, AGENT_LIMIT, expected, candidates, context);
        record(caseName, measured);
        return measured;
    }

    /** 真实检索链路，只把查询向量换成合成向量。 */
    private List<DocumentSearchHit> runSearch(
            String query, UUID generationId, List<UUID> documentIds, int topK) {
        when(embeddings.embed(any(), anyList(), any())).thenReturn(new EmbeddingBatch(
                PROVIDER, MODEL, DIMENSION, List.of(SyntheticEmbedder.embed(query)),
                FINGERPRINT, generationId));
        return search.search(project, query, documentIds, topK);
    }

    private static void record(String caseName, Map<String, Object> measured) {
        EVIDENCE.put(caseName, measured);
    }

    // ---- 种子数据 ----

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, "rag-" + id.toString().substring(0, 8), "test-only-hash", "Retrieval");
        return id;
    }

    private UUID insertProject() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                id, "检索基线", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", id, user);
        return id;
    }

    private UUID insertEmbeddingConfig() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,
                  batch_size,fingerprint,enabled)
                VALUES (?,?,'http://127.0.0.1:1','/v1/embeddings',?,?,16,?,TRUE)
                """, id, PROVIDER, MODEL, DIMENSION, FINGERPRINT);
        return id;
    }

    private UUID insertGeneration(UUID configId, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,?)",
                id, configId, state);
        return id;
    }

    private UUID insertDocument(String filename) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,status,chunk_count,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,uploaded_by,indexed_at)
                VALUES (?,?,?,?,'text/markdown',100,?,'READY',0,?,?,?,?,?,now())
                """, id, project, filename, filename, "projects/" + project + "/" + id + ".md",
                PROVIDER, MODEL, DIMENSION, FINGERPRINT, user);
        return id;
    }

    private UUID insertChunk(UUID document, int chunkNo, String heading, String content) {
        return insertChunk(document, chunkNo, heading, content, null);
    }

    private UUID insertChunk(UUID document, int chunkNo, String heading, String content, UUID generation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,
                  content_hash,token_estimate,metadata,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,embedding,generation_id)
                VALUES (?,?,?,?,?,?,?,?,CAST('{}' AS jsonb),?,?,?,?,CAST(? AS vector),?)
                """, id, project, document, chunkNo, heading, content, sha256(content),
                Math.max(1, content.length() / 4), PROVIDER, MODEL, DIMENSION, FINGERPRINT,
                SyntheticEmbedder.literal(content), generation);
        return id;
    }

    private void insertUnrelatedNoise(UUID document, int fromChunkNo, int count) {
        for (int i = 0; i < count; i++) {
            insertChunk(document, fromChunkNo + i, "噪声",
                    "unrelated quarterly catering menu rotation item " + i);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算分块内容哈希", exception);
        }
    }
}
