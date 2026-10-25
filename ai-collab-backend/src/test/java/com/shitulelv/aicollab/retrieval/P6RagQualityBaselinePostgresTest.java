package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-RAG-QUALITY-01 <b>D0/D1/D2 一体化真实基线测量</b>（本地 Ollama 向量 + 一次性 Testcontainers pgvector）。
 *
 * <p>语料装载复用 {@code GeminiKnowledgeAnswerQualityPostgresTest} 的冻结协议：以
 * {@code corpus-manifest.json} 的 14 篇（13 历史 + 1 注入）逐字节 sha256 校验后按生产
 * {@code DocumentChunker}(1200/150) 与真实本地 Ollama 向量索引；注入样本按 Q13 协议临时索引后失效。
 * <b>不调用任何外部 Chat 模型。</b></p>
 *
 * <p>两条生产路径分开测量、互不混算：
 * QA = {@code search(..., 8)} + 生产 {@code buildBalancedWithDiagnostics(..., 5)}（每文档名额均衡，P6 S2），
 * 另记 legacy（{@code buildWithDiagnostics(..., 5)}，改动前行为）与 S1（两阶段跨文档选择）对照；
 * Agent = {@code search(..., 20)} + {@code buildDiverseWithDiagnostics(..., 8)}（附 limit=12 对照）
 * + 工具侧 600 字 quote 截断。</p>
 *
 * <p>受控实验（全部为测试侧候选池/选择对照，生产代码零改动）：
 * <ul>
 * <li><b>S1</b>（D2）：QA 的 Top8 候选在同一 5 来源名额与 8000 预算下改用两阶段跨文档选择
 *     {@code buildDiverseWithDiagnostics(qaCandidates, 5)}；</li>
 * <li><b>R1a</b>（D1）：仅用 query 中明确可抽取的 ASCII 编号/《》标题做一条有界 SQL LIKE 精确补充
 *     （≤4 条、生产过滤条件、0 次额外向量调用）；</li>
 * <li><b>R1b</b>（D1）：query 的中文二元组在项目分块上的命中数排序补充（同一有界上限），
 *     真实测量中文词法补充对候选池与最终证据的影响。</li>
 * </ul></p>
 *
 * <p>每事实记录分层归因 INDEX_MISS / RETRIEVAL_MISS / SELECTION_MISS:原因 / BODY_TRUNCATION / RETAINED；
 * 全量排序仅作诊断、绝不回填生产候选。输出只写 {@code target/retrieval-baseline/p6-rag-quality-01/}，
 * 不覆盖 2026-10-22 历史冻结证据。整类由 {@code AI_REAL_OLLAMA_RETRIEVAL=true} 门控。</p>
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
@EnabledIfEnvironmentVariable(named = "AI_REAL_OLLAMA_RETRIEVAL", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class P6RagQualityBaselinePostgresTest {
    private static final String PROVIDER = "OLLAMA";
    private static final String EMBEDDING_MODEL = "qwen3-embedding:0.6b";
    private static final String FINGERPRINT = EmbeddingFingerprints.fingerprint(PROVIDER, EMBEDDING_MODEL, 1024);
    private static final int QA_TOP_K = 8;
    private static final int QA_MAX_SOURCES = 5;
    private static final int AGENT_CANDIDATE_POOL = 20;
    private static final int AGENT_DEFAULT_LIMIT = 8;
    private static final int AGENT_QUOTE_CAP = 600;
    /** R1 补充候选的硬上限：任何变体每次检索最多补充 4 条，查询数上界恒为每变体 1 条 SQL。 */
    private static final int R1_SUPPLEMENT_CAP = 4;
    private static final int R1_BIGRAM_MIN_HITS = 2;
    private static final Pattern ASCII_EXPLICIT_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{2,}");
    private static final Pattern HAN_RUN = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern TITLE_TOKEN = Pattern.compile("[《][^《》]{1,200}[》]");
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("tests/fixtures/documents/p4-rag-answer-quality-01");
    private static final Path QUESTIONS =
            ROOT.resolve("docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/questions.json");
    private static final Path MANIFEST =
            ROOT.resolve("docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/corpus-manifest.json");
    /** 只写入 target/，绝不覆盖仓库里 2026-10-22 的冻结验收快照。 */
    private static final Path OUTPUT = Path.of(System.getProperty("rag.baseline.output",
            "target/retrieval-baseline/p6-rag-quality-01/d0-d2-real.json"));
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一条冻结事实：目标文档 + 语料 verbatim 标记（按 marker 定位 chunk，可容忍分块标题合并）。 */
    private record ExpectedFact(String document, String marker) {}

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
            Map.entry("Q07", List.of()),
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
            // Q12 是 Q11 的追问；本测试按首轮提问口径测原始 query（会话改写由 QA 服务在真实会话中完成）。
            Map.entry("Q12", List.of(
                    new ExpectedFact("任务预算规则.md", "不得自动超支"))),
            Map.entry("Q13", List.of(
                    new ExpectedFact("不可信资料测试.md", "书面批准"))),
            Map.entry("Q14", List.of(
                    new ExpectedFact("容量与性能约束.md", "五百份"),
                    new ExpectedFact("容量与性能约束.md", "二十兆字节"))),
            Map.entry("Q15", List.of(
                    new ExpectedFact("容量与性能约束.md", "百分之九十五"),
                    new ExpectedFact("容量与性能约束.md", "不得超过五十次"))),
            Map.entry("Q16", List.of(
                    new ExpectedFact("安全与脱敏规范.md", "不能下载文档的原始文件"),
                    new ExpectedFact("安全与脱敏规范.md", "保留首尾各两位"))));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @MockitoBean
    TaskPlanRecoveryJob taskPlanRecoveryJob;

    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentSearchService search;
    @Autowired DocumentRepository documents;
    @Autowired ProjectEmbeddingGateway embeddings;
    @Autowired DocumentChunker chunker;

    private final KnowledgeContextBuilder builder = new KnowledgeContextBuilder();
    protected UUID user;
    protected UUID project;
    private UUID generation;
    protected final Map<String, UUID> documentIds = new LinkedHashMap<>();
    private final List<Map<String, Object>> corpus = new ArrayList<>();
    private long indexLatencyMs;
    private long indexInputCodePoints;
    private int indexEmbeddingRequests;

    @Test
    void frozenQuestionBaselineAndControlledExperiments() throws Exception {
        seedCorpus();
        JsonNode questions = JSON.readTree(QUESTIONS.toFile());
        List<Map<String, Object>> questionRows = new ArrayList<>();

        for (JsonNode question : questions) {
            String id = question.path("id").asText();
            if ("Q13".equals(id)) indexDocument(FIXTURES.resolve("不可信资料测试.md"));
            Map<String, Object> measured = measureQuestion(question);
            questionRows.add(measured);
            observeQuestion(question, measured);
            if ("Q13".equals(id)) {
                // 与历史协议一致：注入样本仅存在于 Q13 期间，保持其余问题的候选竞争不变。
                jdbc.update("UPDATE project_document SET status='FAILED' WHERE id=?",
                        documentIds.get("不可信资料测试.md"));
            }
        }

        Map<String, Object> summary = summarize(questionRows);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evaluation", "P6-RAG-QUALITY-01 D0/D1/D2 frozen-question real baseline and controlled experiments");
        evidence.put("baseline", System.getProperty("rag.baseline.sha",
                "UNSPECIFIED: supply -Drag.baseline.sha=<current HEAD>"));
        evidence.put("model", EMBEDDING_MODEL);
        evidence.put("dimension", 1024);
        evidence.put("fingerprint", FINGERPRINT);
        evidence.put("generationId", generation.toString());
        evidence.put("chunker", "production DocumentChunker 1200/150");
        evidence.put("corpus", corpus);
        evidence.put("indexing", Map.of(
                "documentCount", corpus.size(),
                "chunkCount", corpus.stream().mapToInt(item -> number(item.get("chunkCount"))).sum(),
                "embeddingInputCodePoints", indexInputCodePoints,
                "embeddingRequests", indexEmbeddingRequests,
                "totalIndexLatencyMs", indexLatencyMs));
        evidence.put("paths", Map.of(
                "qa", Map.of("topK", QA_TOP_K, "maxSources", QA_MAX_SOURCES, "promptBudgetCodePoints", 8000),
                "agent", Map.of("candidatePool", AGENT_CANDIDATE_POOL, "defaultLimit", AGENT_DEFAULT_LIMIT,
                        "quoteCapCodePoints", AGENT_QUOTE_CAP, "promptBudgetCodePoints", 8000)));
        evidence.put("experimentSemantics", Map.of(
                "S1", "QA Top8 候选改用两阶段跨文档选择（同 5 名额、同 8000 预算），测试侧对照",
                "R1a", "query 明确 ASCII 编号/《》标题的有界 SQL LIKE 精确补充（≤4 条、1 条 SQL、0 次额外向量调用）",
                "R1b", "query 中文二元组命中数排序的有界补充（同上限），真实测量中文词法有效性"));
        evidence.put("questions", questionRows);
        evidence.put("summary", summary);
        Files.createDirectories(OUTPUT.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.toFile(), evidence);
        System.out.println("P6_RAG_QUALITY_D0_BASELINE=" + OUTPUT.toAbsolutePath());

        // ---- 机械硬不变量（与语义评分无关）----
        for (Map<String, Object> row : questionRows) {
            String id = (String) row.get("id");
            assertThat(number(row.get("qaPromptCodePoints")))
                    .as("%s：QA 上下文预算不得超过 8000 code point", id).isLessThanOrEqualTo(8000);
            assertThat(number(row.get("agentPromptCodePoints")))
                    .as("%s：Agent 上下文预算不得超过 8000 code point", id).isLessThanOrEqualTo(8000);
            assertThat(number(row.get("qaSourceCount"))).as("%s：QA 来源数上限 5", id).isLessThanOrEqualTo(5);
            assertThat(number(row.get("agentSourceCount"))).as("%s：Agent 默认来源数上限 8", id)
                    .isLessThanOrEqualTo(AGENT_DEFAULT_LIMIT);
            assertThat(number(row.get("scopeViolationCount")))
                    .as("%s：指定文档范围的候选与来源不得越界", id).isZero();
            assertThat(number(row.get("r1SupplementCapExceeded")))
                    .as("%s：R1 补充候选不得超过上限", id).isZero();
        }
        // D04：Q09 在当前 SHA 上重新实测——两份合同必须都保留独立来源身份（QA 与 Agent 两条路径）。
        Map<String, Object> q09 = questionRows.stream()
                .filter(row -> "Q09".equals(row.get("id"))).findFirst().orElseThrow();
        assertThat(facts(q09)).as("Q09 必须包含两条独立事实").hasSize(2);
        assertThat(facts(q09)).extracting(fact -> fact.get("qaStage"))
                .as("Q09：QA 路径两份合同来源均保留").containsOnly("RETAINED");
        assertThat(facts(q09)).extracting(fact -> fact.get("agentStage"))
                .as("Q09：Agent 路径两份合同来源均保留").containsOnly("RETAINED");
        // D12 真实数据零回归门槛：legacy（A）已保留的事实不得因每文档名额均衡（S2）丢失。
        for (Map<String, Object> row : questionRows) {
            for (Map<String, Object> fact : facts(row)) {
                if ("RETAINED".equals(fact.get("qaLegacyStage"))) {
                    assertThat(fact.get("qaStage"))
                            .as("%s/%s：legacy 已保留事实不得因名额均衡丢失",
                                    row.get("id"), fact.get("marker"))
                            .isEqualTo("RETAINED");
                }
            }
        }
        // 目标缺口改善（D03 验收）：Q03 上线/灰度/回滚三阶段证据在生产 QA 路径全部保留。
        Map<String, Object> q03 = questionRows.stream()
                .filter(row -> "Q03".equals(row.get("id"))).findFirst().orElseThrow();
        assertThat(facts(q03)).as("Q03 必须包含三条阶段事实").hasSize(3);
        assertThat(facts(q03)).extracting(fact -> fact.get("qaStage"))
                .as("Q03：名额均衡后三阶段证据（含历史被挤掉的上线门禁）全部进入 QA 上下文")
                .containsOnly("RETAINED");
    }

    /** Test-only extension point: observe the same live index before Q13 is deactivated. */
    protected void observeQuestion(JsonNode question, Map<String, Object> measured) throws Exception {
    }

    // ---- 语料装载 ----

    /** 冻结 manifest 驱动装载：14 篇逐字节 sha256 校验；13 篇常驻索引，注入样本按 Q13 协议临时索引。 */
    private void seedCorpus() throws Exception {
        user = UUID.randomUUID();
        project = UUID.randomUUID();
        generation = UUID.randomUUID();
        jdbc.execute("""
                TRUNCATE document_chunk, project_document, project_member, project, app_user,
                         embedding_index_generation, system_embedding_config CASCADE
                """);
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "p6-rag-" + user.toString().substring(0, 8), "test-only-hash", "P6Baseline");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "P6 RAG 质量基线", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", project, user);
        UUID configId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,
                  batch_size,fingerprint,enabled)
                VALUES (?,'OLLAMA','http://127.0.0.1:11434','/v1/embeddings',?,1024,32,?,TRUE)
                """, configId, EMBEDDING_MODEL, FINGERPRINT);
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'ACTIVE')",
                generation, configId);
        jdbc.update("UPDATE system_embedding_config SET generation_id=? WHERE id=?", generation, configId);
        JsonNode manifest = JSON.readTree(MANIFEST.toFile());
        for (JsonNode entry : manifest.path("documents")) {
            String filename = entry.path("filename").asText();
            Path file = FIXTURES.resolve(filename);
            assertThat(Files.exists(file)).as("冻结语料必须存在: %s", filename).isTrue();
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(sha256(raw))
                    .as("冻结语料 %s 必须与 manifest 哈希逐字节一致", filename)
                    .isEqualTo(entry.path("sha256").asText());
            if ("不可信资料测试.md".equals(filename)) continue;
            indexDocument(file);
        }
        assertThat(documentIds).hasSize(13);
    }

    private void indexDocument(Path file) throws Exception {
        String filename = file.getFileName().toString();
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        var chunks = chunker.split(raw);
        long started = System.nanoTime();
        EmbeddingBatch vectors = embeddings.embed(project,
                chunks.stream().map(chunk -> chunk.content()).toList(), EmbeddingProgressListener.NONE);
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        assertThat(vectors.dimension()).isEqualTo(1024);
        assertThat(vectors.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(vectors.generationId()).isEqualTo(generation);
        UUID document = UUID.randomUUID();
        documentIds.put(filename, document);
        jdbc.update("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,status,chunk_count,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,uploaded_by,indexed_at)
                VALUES (?,?,?,?,'text/markdown',100,?,'READY',?,'OLLAMA',?,1024,?,?,now())
                """, document, project, filename, filename, "p6/" + document, chunks.size(),
                EMBEDDING_MODEL, FINGERPRINT, user);
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            jdbc.update("""
                    INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,
                      content_hash,token_estimate,metadata,embedding_provider,embedding_model,
                      embedding_dimension,embedding_fingerprint,embedding,generation_id)
                    VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),'OLLAMA',?,1024,?,CAST(? AS vector),?)
                    """, UUID.randomUUID(), project, document, chunk.chunkNo(), chunk.heading(),
                    chunk.content(), chunk.contentHash(), chunk.tokenEstimate(),
                    JSON.writeValueAsString(chunk.metadata()), EMBEDDING_MODEL, FINGERPRINT,
                    vectors.vectors().get(index).toString(), generation);
        }
        indexLatencyMs += latencyMs;
        indexInputCodePoints += chunks.stream()
                .mapToInt(chunk -> chunk.content().codePointCount(0, chunk.content().length())).sum();
        indexEmbeddingRequests++;
        corpus.add(new LinkedHashMap<>(Map.of(
                "filename", filename,
                "sha256", sha256(raw),
                "chunkCount", chunks.size(),
                "indexLatencyMs", latencyMs)));
    }

    // ---- 单题测量 ----

    private Map<String, Object> measureQuestion(JsonNode question) {
        String id = question.path("id").asText();
        String query = question.path("query").asText();
        List<UUID> scope = new ArrayList<>();
        question.path("scope").forEach(name -> scope.add(documentIds.get(name.asText())));

        long qaStarted = System.nanoTime();
        List<DocumentSearchHit> qaCandidates = search.search(project, query, scope, QA_TOP_K);
        long qaLatencyMs = (System.nanoTime() - qaStarted) / 1_000_000L;
        // 改动后生产 QA 入口 = 每文档名额均衡（S2）；legacy 为改动前行为（A）对照。
        var qaBuild = builder.buildBalancedWithDiagnostics(qaCandidates, QA_MAX_SOURCES);
        KnowledgeContext qaContext = qaBuild.context();
        var qaLegacyBuild = builder.buildWithDiagnostics(qaCandidates, QA_MAX_SOURCES);
        var qaS1Build = builder.buildDiverseWithDiagnostics(qaCandidates, QA_MAX_SOURCES);

        long agentStarted = System.nanoTime();
        List<DocumentSearchHit> agentCandidates = search.search(project, query, scope, AGENT_CANDIDATE_POOL);
        long agentLatencyMs = (System.nanoTime() - agentStarted) / 1_000_000L;
        var agentBuild = builder.buildDiverseWithDiagnostics(agentCandidates, AGENT_DEFAULT_LIMIT);
        KnowledgeContext agentContext = agentBuild.context();
        var agent12Build = builder.buildDiverseWithDiagnostics(agentCandidates, 12);

        // 诊断专用全量排序：绝不回填生产候选，也不计入生产成本。
        long diagnosticStarted = System.nanoTime();
        List<Double> queryVector = embeddings.embed(project, List.of(query),
                EmbeddingProgressListener.NONE).vectors().getFirst();
        List<DocumentSearchHit> fullRanking = documents.search(project, queryVector,
                PROVIDER, EMBEDDING_MODEL, 1024, FINGERPRINT, scope, 1000, generation);
        long diagnosticLatencyMs = (System.nanoTime() - diagnosticStarted) / 1_000_000L;

        // ---- D1 受控补充实验（每变体 1 条有界 SQL，0 次额外向量调用）----
        Map<String, Object> r1a = supplementByExplicitTokens(query, scope, qaCandidates, agentCandidates, fullRanking);
        Map<String, Object> r1b = supplementByHanBigrams(query, scope, qaCandidates, agentCandidates, fullRanking);
        @SuppressWarnings("unchecked")
        List<DocumentSearchHit> r1aQaPool = (List<DocumentSearchHit>) r1a.get("qaPool");
        @SuppressWarnings("unchecked")
        List<DocumentSearchHit> r1aAgentPool = (List<DocumentSearchHit>) r1a.get("agentPool");
        @SuppressWarnings("unchecked")
        List<DocumentSearchHit> r1bQaPool = (List<DocumentSearchHit>) r1b.get("qaPool");
        @SuppressWarnings("unchecked")
        List<DocumentSearchHit> r1bAgentPool = (List<DocumentSearchHit>) r1b.get("agentPool");
        var r1aQaBuild = builder.buildWithDiagnostics(r1aQaPool, QA_MAX_SOURCES);
        var r1aAgentBuild = builder.buildDiverseWithDiagnostics(r1aAgentPool, AGENT_DEFAULT_LIMIT);
        var r1bQaBuild = builder.buildWithDiagnostics(r1bQaPool, QA_MAX_SOURCES);
        var r1bAgentBuild = builder.buildDiverseWithDiagnostics(r1bAgentPool, AGENT_DEFAULT_LIMIT);

        int scopeViolations = 0;
        for (List<DocumentSearchHit> pool : List.of(qaCandidates, agentCandidates, r1aQaPool, r1aAgentPool,
                r1bQaPool, r1bAgentPool)) {
            for (DocumentSearchHit hit : pool) {
                if (!scope.isEmpty() && !scope.contains(hit.documentId())) scopeViolations++;
            }
        }
        for (KnowledgeSource source : qaContext.sources()) {
            if (!scope.isEmpty() && !scope.contains(source.documentId())) scopeViolations++;
        }
        for (KnowledgeSource source : agentContext.sources()) {
            if (!scope.isEmpty() && !scope.contains(source.documentId())) scopeViolations++;
        }

        List<Map<String, Object>> factRows = new ArrayList<>();
        for (ExpectedFact fact : FACTS.getOrDefault(id, List.of())) {
            Map<String, Object> item = measureFact(fact, fullRanking);
            item.put("qaStage", stage(chunkIdOf(item), fact.marker(), qaCandidates, qaBuild, false));
            item.put("qaLegacyStage", stage(chunkIdOf(item), fact.marker(), qaCandidates, qaLegacyBuild, false));
            item.put("qaS1Stage", stage(chunkIdOf(item), fact.marker(), qaCandidates, qaS1Build, false));
            item.put("agentStage", stage(chunkIdOf(item), fact.marker(), agentCandidates, agentBuild, true));
            item.put("agent12Stage", stage(chunkIdOf(item), fact.marker(), agentCandidates, agent12Build, true));
            item.put("r1aQaStage", stage(chunkIdOf(item), fact.marker(), r1aQaPool, r1aQaBuild, false));
            item.put("r1aAgentStage", stage(chunkIdOf(item), fact.marker(), r1aAgentPool, r1aAgentBuild, true));
            item.put("r1bQaStage", stage(chunkIdOf(item), fact.marker(), r1bQaPool, r1bQaBuild, false));
            item.put("r1bAgentStage", stage(chunkIdOf(item), fact.marker(), r1bAgentPool, r1bAgentBuild, true));
            factRows.add(item);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("query", query);
        row.put("userScope", scope.isEmpty() ? "ALL_PROJECT" : "DOCUMENT_SCOPED");
        row.put("scopeDocuments", question.path("scope").toString());
        row.put("abstain", question.path("abstain").asBoolean());
        row.put("expectedFactCount", factRows.size());
        row.put("facts", factRows);
        row.put("qaCandidateCount", qaCandidates.size());
        row.put("qaCandidates", candidateSummary(qaCandidates));
        row.put("diagnosticFullRanking", candidateSummary(fullRanking));
        row.put("qaDropReasons", dropSummary(qaBuild));
        row.put("qaLegacyDropReasons", dropSummary(qaLegacyBuild));
        row.put("qaLegacyFactStages", factStages(factRows, "qaLegacyStage"));
        row.put("qaS1SourceCount", qaS1Build.context().sources().size());
        row.put("qaS1FactStages", factStages(factRows, "qaS1Stage"));
        row.put("qaSourceCount", qaContext.sources().size());
        row.put("qaSources", sourceSummary(qaContext));
        row.put("qaPromptSources", qaContext.promptSources());
        row.put("qaPromptCodePoints", promptCodePoints(qaContext));
        row.put("agentCandidateCount", agentCandidates.size());
        row.put("agentCandidates", candidateSummary(agentCandidates));
        row.put("agentDropReasons", dropSummary(agentBuild));
        row.put("agentSourceCount", agentContext.sources().size());
        row.put("agentSources", sourceSummary(agentContext));
        row.put("agentPromptSources", agentContext.promptSources());
        row.put("agentPromptCodePoints", promptCodePoints(agentContext));
        row.put("agent12SourceCount", agent12Build.context().sources().size());
        row.put("agent12FactStages", factStages(factRows, "agent12Stage"));
        row.put("agentQuoteTotalCodePoints", agentContext.sources().stream()
                .mapToInt(source -> quoteCap(source.content()).codePointCount(
                        0, quoteCap(source.content()).length())).sum());
        row.put("r1a", r1a.get("report"));
        row.put("r1b", r1b.get("report"));
        row.put("r1SupplementCapExceeded",
                number(((Map<?, ?>) r1a.get("report")).get("supplementCount")) > R1_SUPPLEMENT_CAP
                        || number(((Map<?, ?>) r1b.get("report")).get("supplementCount")) > R1_SUPPLEMENT_CAP
                        ? 1 : 0);
        row.put("scopeViolationCount", scopeViolations);
        row.put("qaLatencyMs", qaLatencyMs);
        row.put("agentLatencyMs", agentLatencyMs);
        row.put("r1aSqlLatencyMs", r1a.get("latencyMs"));
        row.put("r1bSqlLatencyMs", r1b.get("latencyMs"));
        row.put("diagnosticFullRankingMs", diagnosticLatencyMs);
        row.put("queryEmbeddingCallsProduction", 2); // QA 与 Agent 各一次，与生产两条路径一致
        row.put("queryEmbeddingCallsDiagnosticOnly", 1);
        return row;
    }

    // ---- D1：受控候选补充 ----

    /**
     * R1a：仅使用 query 中明确可抽取的 ASCII 编号（含数字且长度≥3）与《》标题，
     * 一条有界 SQL LIKE 补充，生产过滤条件（project/READY/generation）+ 用户 scope。
     */
    private Map<String, Object> supplementByExplicitTokens(String query, List<UUID> scope,
                                                           List<DocumentSearchHit> qaCandidates,
                                                           List<DocumentSearchHit> agentCandidates,
                                                           List<DocumentSearchHit> fullRanking) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher ascii = ASCII_EXPLICIT_TOKEN.matcher(query);
        while (ascii.find()) {
            String token = ascii.group();
            if (token.length() >= 3 && token.chars().anyMatch(Character::isDigit)) tokens.add(token);
        }
        Matcher title = TITLE_TOKEN.matcher(query);
        while (title.find()) tokens.add(title.group());
        return supplementByTokens("R1a", tokens, query, scope, qaCandidates, agentCandidates, fullRanking);
    }

    /** R1b：query 的中文二元组在项目分块上的不同命中数排序（阈值 ≥2），同一有界补充上限。 */
    private Map<String, Object> supplementByHanBigrams(String query, List<UUID> scope,
                                                       List<DocumentSearchHit> qaCandidates,
                                                       List<DocumentSearchHit> agentCandidates,
                                                       List<DocumentSearchHit> fullRanking) {
        Set<String> bigrams = new LinkedHashSet<>();
        Matcher runs = HAN_RUN.matcher(query);
        while (runs.find()) {
            String run = runs.group();
            for (int index = 0; index + 2 <= run.length(); index += 1) {
                bigrams.add(run.substring(index, index + 2));
            }
        }
        Map<String, Object> result = supplementByTokens("R1b", Set.of(), query, scope,
                qaCandidates, agentCandidates, fullRanking);
        if (bigrams.isEmpty()) return result;
        // 二元组命中在 SQL 侧无法高效聚合，这里按项目全量分块（有界于项目语料）在测试侧重算；
        // 行扫描数如实记录为该变体的 I/O 成本。
        long started = System.nanoTime();
        StringBuilder filter = new StringBuilder(
                "SELECT c.id, c.document_id, d.original_filename, c.heading, c.content_hash, c.content "
                        + "FROM document_chunk c JOIN project_document d ON d.id = c.document_id "
                        + "WHERE c.project_id = ? AND c.generation_id = ? AND d.status = 'READY'");
        List<Object> args = new ArrayList<>(List.of(project, generation));
        if (!scope.isEmpty()) {
            filter.append(" AND c.document_id IN (")
                    .append(String.join(",", scope.stream().map(ignored -> "?").toList())).append(")");
            args.addAll(scope);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(filter.toString(), args.toArray());
        Set<UUID> alreadyPooled = new LinkedHashSet<>();
        for (DocumentSearchHit hit : qaCandidates) alreadyPooled.add(hit.id());
        for (DocumentSearchHit hit : agentCandidates) alreadyPooled.add(hit.id());
        record Scored(UUID id, int hits) {}
        List<Scored> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID chunkId = UUID.fromString(row.get("id").toString());
            if (alreadyPooled.contains(chunkId)) continue;
            String text = String.valueOf(row.getOrDefault("heading", "")) + "\n"
                    + String.valueOf(row.getOrDefault("content", ""));
            int hits = 0;
            for (String bigram : bigrams) {
                if (text.contains(bigram)) hits++;
            }
            if (hits >= R1_BIGRAM_MIN_HITS) scored.add(new Scored(chunkId, hits));
        }
        scored.sort((left, right) -> {
            int byHits = Integer.compare(right.hits(), left.hits());
            return byHits != 0 ? byHits : left.id().compareTo(right.id());
        });
        List<DocumentSearchHit> supplements = new ArrayList<>();
        for (Scored item : scored) {
            if (supplements.size() >= R1_SUPPLEMENT_CAP) break;
            DocumentSearchHit hit = hitById(item.id(), rows);
            if (hit != null) supplements.add(hit);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        return assembleSupplementPools("R1b", query, qaCandidates, agentCandidates, fullRanking,
                supplements, latencyMs, rows.size());
    }

    private Map<String, Object> supplementByTokens(String label, Set<String> tokens, String query,
                                                   List<UUID> scope, List<DocumentSearchHit> qaCandidates,
                                                   List<DocumentSearchHit> agentCandidates,
                                                   List<DocumentSearchHit> fullRanking) {
        long latencyMs = 0;
        List<DocumentSearchHit> supplements = new ArrayList<>();
        int rowsScanned = 0;
        if (!tokens.isEmpty()) {
            long started = System.nanoTime();
            StringBuilder sql = new StringBuilder(
                    "SELECT c.id, c.document_id, d.original_filename, c.heading, c.content_hash, c.content "
                            + "FROM document_chunk c JOIN project_document d ON d.id = c.document_id "
                            + "WHERE c.project_id = ? AND c.generation_id = ? AND d.status = 'READY' AND (");
            List<Object> args = new ArrayList<>(List.of(project, generation));
            for (String token : tokens) {
                if (!args.isEmpty() && args.size() > 2) sql.append(" OR ");
                sql.append("c.content LIKE CONCAT('%', ?, '%')");
                args.add(token);
            }
            sql.append(")");
            if (!scope.isEmpty()) {
                sql.append(" AND c.document_id IN (")
                        .append(String.join(",", scope.stream().map(ignored -> "?").toList())).append(")");
                args.addAll(scope);
            }
            sql.append(" LIMIT ").append(R1_SUPPLEMENT_CAP * 4);
            List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
            rowsScanned = rows.size();
            Set<UUID> alreadyPooled = new LinkedHashSet<>();
            for (DocumentSearchHit hit : qaCandidates) alreadyPooled.add(hit.id());
            for (DocumentSearchHit hit : agentCandidates) alreadyPooled.add(hit.id());
            for (Map<String, Object> row : rows) {
                if (supplements.size() >= R1_SUPPLEMENT_CAP) break;
                UUID chunkId = UUID.fromString(row.get("id").toString());
                if (alreadyPooled.contains(chunkId)) continue;
                DocumentSearchHit hit = hitById(chunkId, rows);
                if (hit != null) supplements.add(hit);
            }
            latencyMs = (System.nanoTime() - started) / 1_000_000L;
        }
        return assembleSupplementPools(label, query, qaCandidates, agentCandidates, fullRanking,
                supplements, latencyMs, rowsScanned);
    }

    private Map<String, Object> assembleSupplementPools(String label, String query,
                                                        List<DocumentSearchHit> qaCandidates,
                                                        List<DocumentSearchHit> agentCandidates,
                                                        List<DocumentSearchHit> fullRanking,
                                                        List<DocumentSearchHit> supplements,
                                                        long latencyMs, int rowsScanned) {
        // 补充候选必须携带其真实向量相似度（来自同代际诊断排序），否则会被 0.15 阈值错误丢弃、归因失真。
        List<DocumentSearchHit> withTrueSimilarity = new ArrayList<>(supplements.size());
        for (DocumentSearchHit hit : supplements) {
            double trueSimilarity = similarity(hit.id(), fullRanking);
            withTrueSimilarity.add(trueSimilarity > 0d
                    ? new DocumentSearchHit(hit.id(), hit.documentId(), hit.originalFilename(),
                            hit.heading(), hit.content(), hit.contentHash(), trueSimilarity, hit.metadata())
                    : hit);
        }
        List<DocumentSearchHit> qaPool = new ArrayList<>(qaCandidates);
        List<DocumentSearchHit> agentPool = new ArrayList<>(agentCandidates);
        qaPool.addAll(withTrueSimilarity);
        agentPool.addAll(withTrueSimilarity);
        List<Map<String, Object>> reportItems = new ArrayList<>();
        for (DocumentSearchHit hit : withTrueSimilarity) {
            reportItems.add(Map.of(
                    "chunkId", hit.id().toString(),
                    "documentId", hit.documentId().toString(),
                    "document", hit.originalFilename(),
                    "content", hit.content() == null ? "" : hit.content(),
                    "contentHash", hit.contentHash() == null ? "" : hit.contentHash(),
                    "heading", hit.heading() == null ? "" : hit.heading(),
                    "vectorSimilarity", similarity(hit.id(), fullRanking),
                    "diagnosticFullRank", rank(hit.id(), fullRanking)));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("label", label);
        report.put("query", query);
        report.put("supplementCount", supplements.size());
        report.put("supplements", reportItems);
        report.put("rowsScanned", rowsScanned);
        report.put("extraEmbeddingCalls", 0);
        report.put("poolSemantics",
                "QA 池=Top8+补充按尾插（保持向量序）；Agent 池=Top20+补充由选择器按真实相似度排序");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("qaPool", qaPool);
        result.put("agentPool", agentPool);
        result.put("report", report);
        result.put("latencyMs", latencyMs);
        return result;
    }

    private DocumentSearchHit hitById(UUID chunkId, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            if (UUID.fromString(row.get("id").toString()).equals(chunkId)) {
                return new DocumentSearchHit(chunkId, UUID.fromString(row.get("document_id").toString()),
                        String.valueOf(row.get("original_filename")),
                        row.get("heading") == null ? null : String.valueOf(row.get("heading")),
                        String.valueOf(row.get("content")),
                        row.get("content_hash") == null ? null : String.valueOf(row.get("content_hash")),
                        0d, Map.of());
            }
        }
        return null;
    }

    // ---- 逐事实归因 ----

    private Map<String, Object> measureFact(ExpectedFact fact, List<DocumentSearchHit> fullRanking) {
        UUID expectedDocument = documentIds.get(fact.document());
        List<Map<String, Object>> matches = expectedDocument == null ? List.of() : jdbc.queryForList("""
                SELECT c.id, c.heading FROM document_chunk c
                JOIN project_document d ON d.id = c.document_id
                WHERE d.id = ? AND d.status = 'READY' AND c.generation_id = ?
                  AND c.content LIKE CONCAT('%', ?, '%')
                """, expectedDocument, generation, fact.marker());
        UUID expectedChunk = matches.isEmpty() ? null : UUID.fromString(matches.getFirst().get("id").toString());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("document", fact.document());
        item.put("heading", matches.isEmpty() ? null : matches.getFirst().get("heading"));
        item.put("marker", fact.marker());
        item.put("indexed", expectedChunk != null);
        item.put("chunkId", expectedChunk == null ? "" : expectedChunk.toString());
        item.put("diagnosticFullRank", rank(expectedChunk, fullRanking));
        item.put("diagnosticSimilarity", similarity(expectedChunk, fullRanking));
        return item;
    }

    private static UUID chunkIdOf(Map<String, Object> factItem) {
        String value = String.valueOf(factItem.get("chunkId"));
        return value.isEmpty() ? null : UUID.fromString(value);
    }

    private static String stage(UUID expectedChunk, String marker, List<DocumentSearchHit> candidates,
                                KnowledgeContextBuilder.BuildResult built, boolean agentQuote) {
        if (expectedChunk == null) return "INDEX_MISS";
        if (rank(expectedChunk, candidates) == 0) return "RETRIEVAL_MISS";
        KnowledgeSource retained = built.context().sources().stream()
                .filter(source -> source.chunkId().equals(expectedChunk)).findFirst().orElse(null);
        if (retained == null) {
            Object reason = built.dropReasons().get(expectedChunk);
            return "SELECTION_MISS:" + (reason == null ? "UNATTRIBUTED" : reason);
        }
        String body = agentQuote ? quoteCap(retained.content()) : retained.content();
        return body.contains(marker) ? "RETAINED" : "BODY_TRUNCATION";
    }

    private static List<String> factStages(List<Map<String, Object>> factRows, String field) {
        return factRows.stream().map(row -> String.valueOf(row.get(field))).toList();
    }

    private static int rank(UUID chunkId, List<DocumentSearchHit> candidates) {
        if (chunkId == null) return 0;
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).id().equals(chunkId)) return index + 1;
        }
        return 0;
    }

    private static double similarity(UUID chunkId, List<DocumentSearchHit> ranking) {
        if (chunkId == null) return 0d;
        for (DocumentSearchHit hit : ranking) {
            if (hit.id().equals(chunkId)) return Math.round(hit.similarity() * 10_000d) / 10_000d;
        }
        return 0d;
    }

    /** 测量用复刻：与 Agent 工具 quote 的 600 code point 截断语义一致（生产代码不改）。 */
    private static String quoteCap(String value) {
        if (value == null) return "";
        int count = value.codePointCount(0, value.length());
        return count <= AGENT_QUOTE_CAP ? value : value.substring(0, value.offsetByCodePoints(0, AGENT_QUOTE_CAP));
    }

    // ---- 汇总 ----

    private static Map<String, Object> summarize(List<Map<String, Object>> questionRows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String stageField : List.of("qaStage", "qaLegacyStage", "qaS1Stage", "agentStage", "agent12Stage",
                "r1aQaStage", "r1aAgentStage", "r1bQaStage", "r1bAgentStage")) {
            int retained = 0;
            int total = 0;
            for (Map<String, Object> row : questionRows) {
                for (Map<String, Object> fact : facts(row)) {
                    total++;
                    if ("RETAINED".equals(fact.get(stageField))) retained++;
                }
            }
            summary.put(stageField, Map.of("retained", retained, "total", total,
                    "ratio", ratio(retained, total)));
        }
        return summary;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> facts(Map<String, Object> row) {
        return (List<Map<String, Object>>) row.get("facts");
    }

    private static List<Map<String, Object>> candidateSummary(List<DocumentSearchHit> candidates) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DocumentSearchHit hit : candidates) {
            items.add(Map.of(
                    "chunkId", hit.id().toString(),
                    "document", hit.originalFilename(),
                    "heading", hit.heading() == null ? "" : hit.heading(),
                    "similarity", Math.round(hit.similarity() * 10_000d) / 10_000d,
                    "contentCodePoints", hit.content() == null ? 0
                            : hit.content().codePointCount(0, hit.content().length())));
        }
        return items;
    }

    private static List<Map<String, Object>> sourceSummary(KnowledgeContext context) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (KnowledgeSource source : context.sources()) {
            items.add(Map.of(
                    "rank", source.rank(),
                    "chunkId", source.chunkId().toString(),
                    "documentId", source.documentId().toString(),
                    "document", source.originalFilename(),
                    "content", source.content() == null ? "" : source.content(),
                    "heading", source.heading() == null ? "" : source.heading(),
                    "similarity", Math.round(source.similarity() * 10_000d) / 10_000d,
                    "retainedCodePoints", source.content() == null ? 0
                            : source.content().codePointCount(0, source.content().length())));
        }
        return items;
    }

    private static Map<String, Object> dropSummary(KnowledgeContextBuilder.BuildResult built) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object reason : built.dropReasons().values()) {
            counts.merge(reason.toString(), 1, Integer::sum);
        }
        return new LinkedHashMap<>(counts);
    }

    private static int promptCodePoints(KnowledgeContext context) {
        return context.promptSources().codePointCount(0, context.promptSources().length());
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0d : Math.round(((double) numerator / denominator) * 10_000d) / 10_000d;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算语料哈希", exception);
        }
    }
}
