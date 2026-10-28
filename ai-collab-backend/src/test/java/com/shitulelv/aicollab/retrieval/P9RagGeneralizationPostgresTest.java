package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.infrastructure.tool.KnowledgeSearchAgentTool;
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
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9-RAG-GENERALIZATION-01 <b>独立泛化基准真实基线测量</b>（本地 Ollama 向量 + 一次性 Testcontainers pgvector）。
 *
 * <p>语料为 {@code tests/fixtures/documents/p9-rag-generalization-01/} 的 23 篇全新合成文档，
 * 与 P4～P8 旧冻结集独立（不同业务域：设备维护 / 服务交接 / 资产盘点 / 审批与合同 / 技术变更 / 审计核对）。
 * 问题清单 dev 21 题 + holdout 18 题（方法学保留集，manifest 与 SHA-256 先于测量冻结，
 * 见 {@code holdout-integrity.json}）。评测标注只用于离线评分，绝不参与检索、来源选择或查询构造。</p>
 *
 * <p>沿用 P6-RAG-QUALITY-01 的生产路径测量协议，生产参数零改动：
 * QA = {@code search(..., 8)} + {@code buildBalancedWithDiagnostics(..., 5)}（8000 code points）；
 * Agent = 真实执行 {@link KnowledgeSearchAgentTool}（候选池 20、默认 limit 8、quote 600 截断），
 * 另记 limit=12 对照；全量排序仅作诊断、绝不回填生产候选。不调用任何外部 Chat 模型，
 * 生成层结论恒为 REAL_GENERATION_NOT_EXECUTED。</p>
 *
 * <p>逐事实分层归因 INDEX_MISS / RETRIEVAL_MISS / SELECTION_MISS:原因 / BODY_TRUNCATION / RETAINED；
 * 无答案题记录 False Evidence 代理指标（干扰文档是否进入最终上下文）；冲突题记录
 * distractFacts（已废止/他中心条款）是否同时被保留为最终证据（VersionConflictExposure）。
 * 输出只写 {@code target/p9-rag-generalization-01/}。整类由 {@code AI_REAL_OLLAMA_RETRIEVAL=true} 显式门控。</p>
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
class P9RagGeneralizationPostgresTest {
    private static final String PROVIDER = "OLLAMA";
    private static final String EMBEDDING_MODEL = "qwen3-embedding:0.6b";
    private static final String FINGERPRINT = EmbeddingFingerprints.fingerprint(PROVIDER, EMBEDDING_MODEL, 1024);
    private static final int QA_TOP_K = 8;
    private static final int QA_MAX_SOURCES = 5;
    private static final int AGENT_CANDIDATE_POOL = 20;
    private static final int AGENT_DEFAULT_LIMIT = 8;
    private static final int PROMPT_BUDGET = 8000;
    private static final int QUOTE_CAP = 600;
    /** 注入样本（F4）按 Q13 协议仅在其归属题期间临时索引，测后立即失效。 */
    private static final String INJECTION_DOC = "F4-外部审计参考摘录-未审核来源.md";
    private static final Set<String> TEMP_INDEX_QUESTIONS =
            Set.of("H-G07-NONE", "H-G07-SIMILAR", "H-G07-INJECT");
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path DIR = ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");
    private static final Path OUTPUT = Path.of(System.getProperty("p9.output",
            "target/p9-rag-generalization-01/retrieval-results.json"));
    private static final ObjectMapper JSON = new ObjectMapper();

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
    @Autowired ProjectAccessGuard access;

    private final KnowledgeContextBuilder builder = new KnowledgeContextBuilder();
    private KnowledgeSearchAgentTool knowledgeTool;
    private UUID user;
    private UUID project;
    private UUID generation;
    private final Map<String, UUID> documentIds = new LinkedHashMap<>();
    private final List<Map<String, Object>> corpus = new ArrayList<>();
    private long indexLatencyMs;
    private long indexInputCodePoints;

    P9RagGeneralizationPostgresTest() {
        knowledgeTool = new KnowledgeSearchAgentTool(access, search, builder, JSON);
    }

    @Test
    void generalizationBaselineMeasurement() throws Exception {
        knowledgeTool = new KnowledgeSearchAgentTool(access, search, builder, JSON);
        seedCorpus();
        List<Map<String, Object>> questionRows = new ArrayList<>();
        questionRows.addAll(measureQuestions("question-manifest.json"));
        questionRows.addAll(measureQuestions("question-manifest-holdout.json"));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evaluation", "P9-RAG-GENERALIZATION-01 independent generalization baseline (real Ollama retrieval, no chat model)");
        evidence.put("verdict", "BASELINE_MEASUREMENT_ONLY");
        evidence.put("generation", "REAL_GENERATION_NOT_EXECUTED");
        evidence.put("baseline", System.getProperty("rag.baseline.sha", "UNSPECIFIED: supply -Drag.baseline.sha=<current HEAD>"));
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
                "totalIndexLatencyMs", indexLatencyMs));
        evidence.put("paths", Map.of(
                "qa", Map.of("topK", QA_TOP_K, "maxSources", QA_MAX_SOURCES, "promptBudgetCodePoints", PROMPT_BUDGET),
                "agent", Map.of("candidatePool", AGENT_CANDIDATE_POOL, "defaultLimit", AGENT_DEFAULT_LIMIT,
                        "quoteCapCodePoints", QUOTE_CAP, "promptBudgetCodePoints", PROMPT_BUDGET)));
        evidence.put("methodNotes", Map.of(
                "holdoutSemantics", "holdout-integrity.json 冻结先于测量；未依据 holdout 结果调整任何生产或评测参数",
                "labelIsolation", "facts/distractFacts 标注仅用于离线评分，不进入检索与来源选择",
                "toolContract", "Agent 实际可见摘录以真实 KnowledgeSearchAgentTool 输出 JSON 为准；composer 传输一致性已在 P8 同 SHA 验证，本轮不重复",
                "quoteSemantics", "quote 为 600 code point 前缀截断，非真实 token 数"));
        evidence.put("questions", questionRows);
        evidence.put("summary", summarize(questionRows));
        evidence.put("dimensionSummary", groupSummary(questionRows, "dimension"));
        evidence.put("lengthSummary", groupSummary(questionRows, "docLengthBand"));
        evidence.put("docCountSummary", groupSummary(questionRows, "docCountBand"));
        evidence.put("factCountSummary", groupSummary(questionRows, "factCountBand"));
        evidence.put("setSummary", groupSummary(questionRows, "set"));

        Files.createDirectories(OUTPUT.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.toFile(), evidence);
        System.out.println("P9_GENERALIZATION_BASELINE=" + OUTPUT.toAbsolutePath());

        // ---- 机械硬不变量（与语义评分无关，任何一题违反即测量无效）----
        for (Map<String, Object> row : questionRows) {
            String id = (String) row.get("id");
            assertThat(number(row.get("qaPromptCodePoints")))
                    .as("%s：QA 上下文预算不得超过 8000 code point", id).isLessThanOrEqualTo(PROMPT_BUDGET);
            assertThat(number(row.get("agentPromptCodePoints")))
                    .as("%s：Agent 上下文预算不得超过 8000 code point", id).isLessThanOrEqualTo(PROMPT_BUDGET);
            assertThat(number(row.get("qaSourceCount"))).as("%s：QA 来源数上限 5", id).isLessThanOrEqualTo(QA_MAX_SOURCES);
            assertThat(number(row.get("agentSourceCount"))).as("%s：Agent 默认来源数上限 8", id)
                    .isLessThanOrEqualTo(AGENT_DEFAULT_LIMIT);
            assertThat(number(row.get("agentToolSourceCount"))).as("%s：Agent 工具默认返回来源数上限 8", id)
                    .isLessThanOrEqualTo(AGENT_DEFAULT_LIMIT);
            assertThat(number(row.get("scopeViolationCount")))
                    .as("%s：指定文档范围的候选与来源不得越界", id).isZero();
            assertThat(number(row.get("identityViolationCount")))
                    .as("%s：工具来源身份（documentId/chunkId/filename/quote 前缀）必须与数据库一致", id).isZero();
        }
    }

    // ---- 语料装载（manifest 驱动，逐字节 sha256 校验）----

    private void seedCorpus() throws Exception {
        user = UUID.randomUUID();
        project = UUID.randomUUID();
        generation = UUID.randomUUID();
        jdbc.execute("""
                TRUNCATE document_chunk, project_document, project_member, project, app_user,
                         embedding_index_generation, system_embedding_config CASCADE
                """);
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "p9-rag-" + user.toString().substring(0, 8), "test-only-hash", "P9Generalization");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "P9 RAG 泛化基准", user, user);
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
        JsonNode manifest = JSON.readTree(DIR.resolve("corpus-manifest.json").toFile());
        for (JsonNode entry : manifest.path("documents")) {
            String filename = entry.path("filename").asText();
            Path file = DIR.resolve(filename);
            assertThat(Files.exists(file)).as("冻结语料必须存在: %s", filename).isTrue();
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(sha256(raw))
                    .as("冻结语料 %s 必须与 manifest 哈希逐字节一致", filename)
                    .isEqualTo(entry.path("sha256").asText());
            if (INJECTION_DOC.equals(filename)) continue;
            indexDocument(file);
        }
        assertThat(documentIds).as("常驻语料 22 篇（注入样本按题临时索引）").hasSize(22);
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
                """, document, project, filename, filename, "p9/" + document, chunks.size(),
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
        corpus.add(new LinkedHashMap<>(Map.of(
                "filename", filename,
                "sha256", sha256(raw),
                "chunkCount", chunks.size(),
                "indexLatencyMs", latencyMs)));
    }

    // ---- 单题测量 ----

    private List<Map<String, Object>> measureQuestions(String manifestFile) throws Exception {
        JsonNode questions = JSON.readTree(DIR.resolve(manifestFile).toFile());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode question : questions.path("questions")) {
            String id = question.path("id").asText();
            boolean tempIndex = TEMP_INDEX_QUESTIONS.contains(id);
            if (tempIndex) indexDocument(DIR.resolve(INJECTION_DOC));
            rows.add(measureQuestion(question));
            if (tempIndex) {
                jdbc.update("UPDATE project_document SET status='FAILED' WHERE id=?",
                        documentIds.get(INJECTION_DOC));
            }
        }
        return rows;
    }

    private Map<String, Object> measureQuestion(JsonNode question) throws Exception {
        String id = question.path("id").asText();
        String query = question.path("query").asText();
        List<UUID> scope = new ArrayList<>();
        question.path("scope").forEach(name -> scope.add(documentIds.get(name.asText())));

        long qaStarted = System.nanoTime();
        List<DocumentSearchHit> qaCandidates = search.search(project, query, scope, QA_TOP_K);
        long qaLatencyMs = (System.nanoTime() - qaStarted) / 1_000_000L;
        var qaBuild = builder.buildBalancedWithDiagnostics(qaCandidates, QA_MAX_SOURCES);
        var qaLegacyBuild = builder.buildWithDiagnostics(qaCandidates, QA_MAX_SOURCES);

        long agentStarted = System.nanoTime();
        List<DocumentSearchHit> agentCandidates = search.search(project, query, scope, AGENT_CANDIDATE_POOL);
        long agentLatencyMs = (System.nanoTime() - agentStarted) / 1_000_000L;
        var agentBuild = builder.buildDiverseWithDiagnostics(agentCandidates, AGENT_DEFAULT_LIMIT);
        var agent12Build = builder.buildDiverseWithDiagnostics(agentCandidates, 12);

        // 真实 Agent 工具执行：与生产同一工具代码路径（候选池 20、limit 8、quote 600 截断、scope 解析）。
        var toolArgs = JSON.createObjectNode().put("query", query);
        if (!scope.isEmpty()) {
            var ids = toolArgs.putArray("documentIds");
            scope.forEach(value -> ids.add(value.toString()));
        }
        long toolStarted = System.nanoTime();
        AgentToolResult toolResult = knowledgeTool.execute(new AgentToolContext(
                UUID.randomUUID(), project, user, "OWNER", false, 0, null, query), toolArgs);
        long toolLatencyMs = (System.nanoTime() - toolStarted) / 1_000_000L;
        JsonNode toolSources = toolResult.data().path("sources");

        // 诊断专用全量排序：绝不回填生产候选，也不计入生产成本。
        long diagnosticStarted = System.nanoTime();
        List<Double> queryVector = embeddings.embed(project, List.of(query),
                EmbeddingProgressListener.NONE).vectors().getFirst();
        List<DocumentSearchHit> fullRanking = documents.search(project, queryVector,
                PROVIDER, EMBEDDING_MODEL, 1024, FINGERPRINT, scope, 1000, generation);
        long diagnosticLatencyMs = (System.nanoTime() - diagnosticStarted) / 1_000_000L;

        // ---- 来源身份与范围核验（真实工具输出 vs 数据库）----
        int identityViolations = 0;
        int scopeViolations = 0;
        List<Map<String, Object>> identityChecks = new ArrayList<>();
        Set<String> toolChunkIds = new LinkedHashSet<>();
        for (JsonNode source : toolSources) {
            String chunk = source.path("chunkId").asText();
            String doc = source.path("documentId").asText();
            toolChunkIds.add(chunk);
            if (!scope.isEmpty() && !scope.contains(UUID.fromString(doc))) scopeViolations++;
            var actual = jdbc.queryForMap(
                    "SELECT c.content,c.document_id,d.original_filename FROM document_chunk c "
                            + "JOIN project_document d ON d.id=c.document_id "
                            + "WHERE c.id=? AND c.project_id=? AND d.status='READY'",
                    UUID.fromString(chunk), project);
            String quote = source.path("quote").asText();
            boolean ok = actual.get("document_id").toString().equalsIgnoreCase(doc)
                    && actual.get("original_filename").toString().equals(source.path("filename").asText())
                    && ((String) actual.get("content")).startsWith(quote.replaceAll("…$", ""))
                    && quote.codePointCount(0, quote.length()) <= QUOTE_CAP;
            if (!ok) identityViolations++;
            identityChecks.add(Map.of("documentId", doc, "chunkId", chunk, "quoteCodePoints",
                    quote.codePointCount(0, quote.length()), "pass", ok));
        }
        for (KnowledgeSource source : qaBuild.context().sources()) {
            if (!scope.isEmpty() && !scope.contains(source.documentId())) scopeViolations++;
        }
        for (DocumentSearchHit hit : qaCandidates) {
            if (!scope.isEmpty() && !scope.contains(hit.documentId())) scopeViolations++;
        }
        for (DocumentSearchHit hit : agentCandidates) {
            if (!scope.isEmpty() && !scope.contains(hit.documentId())) scopeViolations++;
        }

        // ---- 逐事实分层归因（事实级：凡内容包含 marker 的 chunk 皆视为该事实的载体，容忍同文重复段落）----
        List<Map<String, Object>> factRows = new ArrayList<>();
        for (JsonNode fact : question.path("facts")) {
            String document = fact.path("document").asText();
            String marker = fact.path("marker").asText();
            Map<String, Object> item = locateFact(document, marker, fullRanking);
            @SuppressWarnings("unchecked")
            List<UUID> chunkIds = (List<UUID>) item.remove("chunkIds");
            item.put("fact", fact.path("fact").asText());
            item.put("chunkId", chunkIds.isEmpty() ? "" : chunkIds.getFirst().toString());
            item.put("carrierChunkCount", chunkIds.size());
            item.put("qaStage", stage(chunkIds, marker, qaCandidates, qaBuild, false));
            item.put("qaLegacyStage", stage(chunkIds, marker, qaCandidates, qaLegacyBuild, false));
            item.put("agentStage", stage(chunkIds, marker, agentCandidates, agentBuild, true));
            item.put("agent12Stage", stage(chunkIds, marker, agentCandidates, agent12Build, true));
            item.put("agentToolStage", toolStage(chunkIds, marker, toolSources));
            item.put("top8Rank", bestRank(chunkIds, qaCandidates));
            item.put("top20Rank", bestRank(chunkIds, agentCandidates));
            factRows.add(item);
        }
        // 冲突条款暴露：已废止/他方条款是否同时被保留为"最终可见证据"。
        List<Map<String, Object>> distractRows = new ArrayList<>();
        for (JsonNode fact : question.path("distractFacts")) {
            String document = fact.path("document").asText();
            String marker = fact.path("marker").asText();
            Map<String, Object> item = locateFact(document, marker, fullRanking);
            @SuppressWarnings("unchecked")
            List<UUID> chunkIds = (List<UUID>) item.remove("chunkIds");
            item.put("note", fact.path("note").asText());
            item.put("qaRetained", "RETAINED".equals(stage(chunkIds, marker, qaCandidates, qaBuild, false)));
            item.put("agentToolRetained", "RETAINED".equals(toolStage(chunkIds, marker, toolSources)));
            distractRows.add(item);
        }

        int sourceCount = toolSources.size();
        int toolQuoteCodePoints = 0;
        Set<String> toolDocs = new LinkedHashSet<>();
        for (JsonNode source : toolSources) {
            String quote = source.path("quote").asText();
            toolQuoteCodePoints += quote.codePointCount(0, quote.length());
            toolDocs.add(source.path("filename").asText());
        }
        // 无答案题的 False Evidence 代理：干扰文档内容是否进入最终上下文/工具摘录。
        List<Map<String, Object>> falseEvidence = new ArrayList<>();
        for (JsonNode name : question.path("distractorDocs")) {
            UUID docId = documentIds.get(name.asText());
            boolean inQa = docId != null && qaBuild.context().sources().stream()
                    .anyMatch(s -> s.documentId().equals(docId));
            boolean inAgentTool = false;
            if (docId != null) {
                for (JsonNode source : toolSources) {
                    if (docId.toString().equalsIgnoreCase(source.path("documentId").asText())) inAgentTool = true;
                }
            }
            falseEvidence.add(Map.of("document", name.asText(), "inQaContext", inQa, "inAgentToolQuote", inAgentTool));
        }

        Set<String> expectedDocs = new LinkedHashSet<>();
        question.path("facts").forEach(f -> expectedDocs.add(f.path("document").asText()));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("set", question.path("set").asText());
        row.put("family", question.path("family").asText());
        row.put("dimension", question.path("dimension").asText());
        row.put("docLengthBand", question.path("docLengthBand").asText());
        row.put("docCountBand", expectedDocs.size() <= 1 ? "1" : expectedDocs.size() + "docs");
        int factCount = factRows.size();
        row.put("factCountBand", factCount == 0 ? "0" : factCount == 1 ? "1" : factCount == 2 ? "2" : "3+");
        row.put("query", query);
        row.put("abstain", question.path("abstain").asBoolean());
        row.put("inject", question.path("inject").asBoolean(false));
        row.put("expectedFactCount", factCount);
        row.put("facts", factRows);
        row.put("distractFacts", distractRows);
        row.put("falseEvidence", falseEvidence);
        row.put("qaCandidateCount", qaCandidates.size());
        row.put("qaCandidates", candidateSummary(qaCandidates));
        row.put("qaDropReasons", dropSummary(qaBuild));
        row.put("qaSourceCount", qaBuild.context().sources().size());
        row.put("qaSources", sourceSummary(qaBuild.context()));
        row.put("qaPromptCodePoints", promptCodePoints(qaBuild.context()));
        row.put("agentCandidateCount", agentCandidates.size());
        row.put("agentCandidates", candidateSummary(agentCandidates));
        row.put("agentDropReasons", dropSummary(agentBuild));
        row.put("agentSourceCount", agentBuild.context().sources().size());
        row.put("agentSources", sourceSummary(agentBuild.context()));
        row.put("agent12SourceCount", agent12Build.context().sources().size());
        row.put("agentToolSourceCount", sourceCount);
        row.put("agentToolDocuments", new ArrayList<>(toolDocs));
        row.put("agentToolQuoteTotalCodePoints", toolQuoteCodePoints);
        row.put("agentPromptCodePoints", promptCodePoints(agentBuild.context()));
        row.put("agentToolUserScopeEnforced", toolResult.data().path("userScopeEnforced").asBoolean());
        row.put("scopeViolationCount", scopeViolations);
        row.put("identityChecks", identityChecks);
        row.put("identityViolationCount", identityViolations);
        row.put("qaLatencyMs", qaLatencyMs);
        row.put("agentLatencyMs", agentLatencyMs);
        row.put("agentToolLatencyMs", toolLatencyMs);
        row.put("diagnosticFullRankingMs", diagnosticLatencyMs);
        row.put("queryEmbeddingCallsProduction", 2);
        row.put("queryEmbeddingCallsDiagnosticOnly", 1);
        return row;
    }

    // ---- 逐事实定位与归因 ----

    private Map<String, Object> locateFact(String document, String marker, List<DocumentSearchHit> fullRanking) {
        UUID expectedDocument = documentIds.get(document);
        List<Map<String, Object>> matches = expectedDocument == null ? List.of() : jdbc.queryForList("""
                SELECT c.id, c.heading FROM document_chunk c
                JOIN project_document d ON d.id = c.document_id
                WHERE d.id = ? AND d.status = 'READY' AND c.generation_id = ?
                  AND c.content LIKE CONCAT('%', ?, '%')
                ORDER BY c.chunk_no
                """, expectedDocument, generation, marker);
        List<UUID> chunkIds = matches.stream()
                .map(row -> UUID.fromString(row.get("id").toString())).toList();
        int bestFullRank = Integer.MAX_VALUE;
        double bestSimilarity = 0d;
        for (UUID chunkId : chunkIds) {
            int candidateRank = rank(chunkId, fullRanking);
            if (candidateRank > 0) {
                bestFullRank = Math.min(bestFullRank, candidateRank);
                bestSimilarity = Math.max(bestSimilarity, similarity(chunkId, fullRanking));
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("document", document);
        item.put("marker", marker);
        item.put("indexed", !chunkIds.isEmpty());
        item.put("chunkIds", chunkIds);
        item.put("heading", matches.isEmpty() ? null : matches.getFirst().get("heading"));
        item.put("diagnosticFullRank", bestFullRank == Integer.MAX_VALUE ? 0 : bestFullRank);
        item.put("diagnosticSimilarity", bestSimilarity);
        return item;
    }

    private static String stage(List<UUID> chunks, String marker, List<DocumentSearchHit> candidates,
                                KnowledgeContextBuilder.BuildResult built, boolean agentQuote) {
        if (chunks.isEmpty()) return "INDEX_MISS";
        if (bestRank(chunks, candidates) == 0) return "RETRIEVAL_MISS";
        for (UUID chunkId : chunks) {
            KnowledgeSource retained = built.context().sources().stream()
                    .filter(source -> source.chunkId().equals(chunkId)).findFirst().orElse(null);
            if (retained != null) {
                String body = agentQuote ? quoteCap(retained.content()) : retained.content();
                if (body.contains(marker)) return "RETAINED";
            }
        }
        for (UUID chunkId : chunks) {
            if (built.context().sources().stream().anyMatch(source -> source.chunkId().equals(chunkId))) {
                return "BODY_TRUNCATION";
            }
        }
        UUID bestChunk = bestRankedChunk(chunks, candidates);
        Object reason = bestChunk == null ? null : built.dropReasons().get(bestChunk);
        return "SELECTION_MISS:" + (reason == null ? "UNATTRIBUTED" : reason);
    }

    /** 基于真实工具输出 JSON 的 Agent 可见性归因（事实级）。 */
    private static String toolStage(List<UUID> chunks, String marker, JsonNode toolSources) {
        if (chunks.isEmpty()) return "INDEX_MISS";
        for (UUID chunkId : chunks) {
            for (JsonNode source : toolSources) {
                if (source.path("chunkId").asText().equalsIgnoreCase(chunkId.toString())
                        && source.path("quote").asText().contains(marker)) {
                    return "RETAINED";
                }
            }
        }
        for (UUID chunkId : chunks) {
            for (JsonNode source : toolSources) {
                if (source.path("chunkId").asText().equalsIgnoreCase(chunkId.toString())) {
                    return "BODY_TRUNCATION";
                }
            }
        }
        return "SELECTION_MISS:NOT_IN_TOOL_RESULT";
    }

    private static int bestRank(List<UUID> chunks, List<DocumentSearchHit> candidates) {
        int best = 0;
        for (UUID chunkId : chunks) {
            int candidateRank = rank(chunkId, candidates);
            if (candidateRank > 0 && (best == 0 || candidateRank < best)) best = candidateRank;
        }
        return best;
    }

    private static UUID bestRankedChunk(List<UUID> chunks, List<DocumentSearchHit> candidates) {
        UUID best = null;
        int bestRank = Integer.MAX_VALUE;
        for (UUID chunkId : chunks) {
            int candidateRank = rank(chunkId, candidates);
            if (candidateRank > 0 && candidateRank < bestRank) {
                bestRank = candidateRank;
                best = chunkId;
            }
        }
        return best;
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

    private static String quoteCap(String value) {
        if (value == null) return "";
        int count = value.codePointCount(0, value.length());
        return count <= QUOTE_CAP ? value : value.substring(0, value.offsetByCodePoints(0, QUOTE_CAP));
    }

    // ---- 汇总 ----

    private static Map<String, Object> summarize(List<Map<String, Object>> questionRows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String stageField : List.of("qaStage", "qaLegacyStage", "agentStage", "agentToolStage", "agent12Stage")) {
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
        // Complete Evidence Coverage：单题全部必答事实均 RETAINED 的题目占比。
        for (String stageField : List.of("qaStage", "agentToolStage")) {
            long complete = questionRows.stream().filter(row -> !facts(row).isEmpty())
                    .filter(row -> facts(row).stream().allMatch(f -> "RETAINED".equals(f.get(stageField)))).count();
            long answerable = questionRows.stream().filter(row -> !facts(row).isEmpty()).count();
            summary.put("completeCoverage:" + stageField, Map.of("questions", complete, "answerable", answerable,
                    "ratio", ratio((int) complete, (int) answerable)));
        }
        return summary;
    }

    private static Map<String, Object> groupSummary(List<Map<String, Object>> questionRows, String groupField) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : questionRows) {
            groups.computeIfAbsent(String.valueOf(row.get(groupField)), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            result.put(entry.getKey(), summarize(entry.getValue()));
        }
        return result;
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
