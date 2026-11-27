package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shitulelv.aicollab.common.ai.token.ModelAwareTokenCounter;
import com.shitulelv.aicollab.common.retrieval.RetrievalScores;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval.IntentRequest;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval.RetrievalIntent;
import com.shitulelv.aicollab.retrieval.pipeline.DenseRetriever;
import com.shitulelv.aicollab.retrieval.pipeline.FusionStrategy;
import com.shitulelv.aicollab.retrieval.pipeline.LexicalRetriever;
import com.shitulelv.aicollab.retrieval.pipeline.NeuralModelReranker;
import com.shitulelv.aicollab.retrieval.pipeline.ParentChildExpander;
import com.shitulelv.aicollab.retrieval.pipeline.PassThroughReranker;
import com.shitulelv.aicollab.retrieval.pipeline.ReciprocalRankFusion;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.pipeline.SemanticHeuristicReranker;
import com.shitulelv.aicollab.retrieval.pipeline.SentenceWindowExpander;
import com.shitulelv.aicollab.retrieval.query.EvidenceSufficiencyProbe;
import com.shitulelv.aicollab.retrieval.query.QueryRouteDecision;
import com.shitulelv.aicollab.retrieval.query.QueryRouter;
import com.shitulelv.aicollab.retrieval.query.StrategyAwareRetrievalExecutor;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约 §12.4 R2-5 & §9.3 第 7 条：39 题泛化基准 + 启发式重排 / RRF 金标准夹具导出器。
 *
 * <p>本类写入 {@code src/test}，不改写现行 Java 检索链路与 5 处平局规则；
 * 仅在显式设置环境变量 {@code AI_RAG_GOLDEN_FIXTURE_EXPORT=true} 且存在可用 Docker 与本地 Ollama 时运行，
 * 默认跳过。导出目录可通过 {@code -Dp2.golden.output.dir=...} 覆盖，默认落
 * {@code target/p2-golden-fixtures/}。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "agent.enabled=false",
        "memory.job.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket"
})
@EnabledIfEnvironmentVariable(named = "AI_RAG_GOLDEN_FIXTURE_EXPORT", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class P2GoldenFixtureExportPostgresTest {

    private static final String PROVIDER = "OLLAMA";
    private static final String DEFAULT_EMBEDDING_MODEL = "qwen3-embedding:0.6b";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1:11434";
    private static final int EMBEDDING_DIMENSION = 1024;
    private static final int QA_TOP_K = 8;
    private static final int QA_MAX_SOURCES = KnowledgeContextBuilder.DEFAULT_MAX_SOURCES;
    private static final int QA_TOKEN_BUDGET = 8000;
    private static final int AGENT_TOP_K = 20;
    private static final String INJECTION_DOC = "F4-外部审计参考摘录-未审核来源.md";
    private static final Set<String> TEMP_INDEX_QUESTIONS = Set.of("H-G07-NONE", "H-G07-SIMILAR", "H-G07-INJECT");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

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

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentChunker chunker;
    @Autowired
    ProjectEmbeddingGateway embeddings;
    @Autowired
    ProjectAccessGuard access;
    @Autowired
    DocumentSearchService search;
    @Autowired
    DenseRetriever denseRetriever;
    @Autowired
    LexicalRetriever lexicalRetriever;
    @Autowired(required = false)
    SentenceWindowExpander sentenceWindowExpander;
    @Autowired(required = false)
    ParentChildExpander parentChildExpander;
    @Autowired(required = false)
    PassThroughReranker passThroughReranker;
    @Autowired
    QueryRouter queryRouter;
    @Autowired
    KnowledgeContextBuilder contextBuilder;
    @Autowired(required = false)
    ModelAwareTokenCounter tokenCounter;

    private final Map<UUID, ChunkIdentity> chunkIdentities = new ConcurrentHashMap<>();
    private final Map<String, UUID> documentIds = new LinkedHashMap<>();

    @Test
    void exportsThirtyNineQuestionsAndPipelineStageGoldenFixtures() throws Exception {
        Path fixtureDir = resolveFixtureDir();
        Path outputDir = Path.of(System.getProperty("p2.golden.output.dir", "target/p2-golden-fixtures"));
        Files.createDirectories(outputDir);

        String model = System.getenv().getOrDefault("AI_OLLAMA_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL);
        String baseUrl = System.getenv().getOrDefault("AI_OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL);
        String fingerprint = EmbeddingFingerprints.fingerprint(PROVIDER, model, EMBEDDING_DIMENSION);

        jdbc.execute("""
                TRUNCATE document_chunk, document_body_chunk, document_body, project_document,
                         project_member, project, app_user,
                         embedding_index_generation, system_embedding_config CASCADE
                """);

        UUID userId = insertUser();
        UUID projectId = insertProject(userId);
        UUID generationId = seedEmbeddingConfig(baseUrl, model, fingerprint);

        List<Map<String, Object>> corpusExport = seedCorpus(fixtureDir, projectId, userId, model, fingerprint, generationId);
        List<JsonNode> questions = loadAllQuestions(fixtureDir);
        assertThat(questions).hasSize(39);

        RecordingContext recorder = new RecordingContext();
        RecordingFusionStrategy recordingFusion = new RecordingFusionStrategy(recorder);
        RecordingHeuristicReranker recordingReranker = new RecordingHeuristicReranker(recorder);
        RecordingQueryRouter recordingRouter = new RecordingQueryRouter(queryRouter, recorder);

        RetrievalPipeline recordingPipeline = new RetrievalPipeline(
                denseRetriever,
                lexicalRetriever,
                recordingFusion,
                sentenceWindowExpander != null ? sentenceWindowExpander : new SentenceWindowExpander(),
                parentChildExpander != null ? parentChildExpander : new ParentChildExpander(),
                recordingReranker,
                passThroughReranker != null ? passThroughReranker : new PassThroughReranker(),
                new NeuralModelReranker(null, recordingReranker, JSON));
        StrategyAwareRetrievalExecutor recordingExecutor = new StrategyAwareRetrievalExecutor(recordingPipeline);
        ProjectKnowledgeRetrieval retrieval = new ProjectKnowledgeRetrieval(
                access, search, recordingPipeline, recordingRouter, recordingExecutor);

        EvidenceSufficiencyProbe qaProbe = candidates -> !assembleQaContext(candidates).sources().isEmpty();

        List<Map<String, Object>> exportedQuestions = new ArrayList<>(questions.size());
        for (JsonNode question : questions) {
            String qid = question.path("id").asText();
            String query = question.path("query").asText();
            boolean temporaryDocActive = TEMP_INDEX_QUESTIONS.contains(qid);
            UUID injectionDocId = documentIds.get(INJECTION_DOC);

            if (temporaryDocActive && injectionDocId != null) {
                jdbc.update("UPDATE project_document SET status='READY' WHERE id=?", injectionDocId);
            }
            try {
                List<String> scopeFilenames = new ArrayList<>();
                List<UUID> scopeIds = new ArrayList<>();
                for (JsonNode scopeItem : question.path("scope")) {
                    String filename = scopeItem.asText();
                    scopeFilenames.add(filename);
                    UUID docId = documentIds.get(filename);
                    assertThat(docId).withFailMessage("Missing scope doc %s for question %s", filename, qid).isNotNull();
                    scopeIds.add(docId);
                }

                Map<String, Object> qaRun = runMode(
                        recorder, qid, "QA", retrieval, projectId, userId, query, scopeIds, QA_TOP_K,
                        RetrievalIntent.KNOWLEDGE_QA, qaProbe);
                Map<String, Object> agentRun = runMode(
                        recorder, qid, "AGENT", retrieval, projectId, userId, query, scopeIds, AGENT_TOP_K,
                        RetrievalIntent.AGENT_RESEARCH, null);

                Map<String, Object> questionRow = new LinkedHashMap<>();
                questionRow.put("id", qid);
                questionRow.put("set", question.path("set").asText());
                questionRow.put("family", question.path("family").asText());
                questionRow.put("dimension", question.path("dimension").asText());
                questionRow.put("query", query);
                questionRow.put("scopeFilenames", scopeFilenames);
                questionRow.put("scopeDocumentIds", scopeIds.stream().map(UUID::toString).toList());
                questionRow.put("abstain", question.path("abstain").asBoolean());
                questionRow.put("qa", qaRun);
                questionRow.put("agent", agentRun);
                exportedQuestions.add(questionRow);
            } finally {
                if (temporaryDocActive && injectionDocId != null) {
                    jdbc.update("UPDATE project_document SET status='FAILED' WHERE id=?", injectionDocId);
                }
            }
        }

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("benchmark", "p9-rag-generalization-01");
        bundle.put("schemaVersion", 1);
        bundle.put("embeddingProvider", PROVIDER);
        bundle.put("embeddingModel", model);
        bundle.put("embeddingDimension", EMBEDDING_DIMENSION);
        bundle.put("embeddingFingerprint", fingerprint);
        bundle.put("documentCount", corpusExport.size());
        bundle.put("questionCount", exportedQuestions.size());
        bundle.put("corpus", corpusExport);
        bundle.put("questions", exportedQuestions);

        Map<String, Object> rerankerBundle = new LinkedHashMap<>();
        rerankerBundle.put("reranker", recordingReranker.name());
        rerankerBundle.put("caseCount", recorder.rerankerCases.size());
        rerankerBundle.put("cases", recorder.rerankerCases);

        Map<String, Object> rrfBundle = new LinkedHashMap<>();
        rrfBundle.put("fusion", "reciprocal_rank_fusion");
        rrfBundle.put("caseCount", recorder.rrfCases.size());
        rrfBundle.put("cases", recorder.rrfCases);

        JSON.writeValue(outputDir.resolve("p9-39q-golden.json").toFile(), bundle);
        JSON.writeValue(outputDir.resolve("reranker-golden.json").toFile(), rerankerBundle);
        JSON.writeValue(outputDir.resolve("rrf-golden.json").toFile(), rrfBundle);
    }

    private Map<String, Object> runMode(
            RecordingContext recorder,
            String questionId,
            String modeLabel,
            ProjectKnowledgeRetrieval retrieval,
            UUID projectId,
            UUID userId,
            String query,
            List<UUID> scopeIds,
            int topK,
            RetrievalIntent intent,
            EvidenceSufficiencyProbe probe) {
        recorder.begin(questionId, modeLabel);
        ProjectKnowledgeRetrieval.Result result = retrieval.retrieve(
                new IntentRequest(projectId, userId, query, scopeIds, topK, intent), probe);
        QueryRouteDecision routeDecision = recorder.lastDecision;
        recorder.end();

        List<Map<String, Object>> hitRows = new ArrayList<>(result.hits().size());
        for (int i = 0; i < result.hits().size(); i++) {
            DocumentSearchHit hit = result.hits().get(i);
            ChunkIdentity identity = resolveIdentity(hit.id(), hit.documentId(), hit.contentHash(), hit.originalFilename());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("documentId", identity.documentId().toString());
            row.put("chunkNo", identity.chunkNo());
            row.put("contentHash", identity.contentHash());
            row.put("originalFilename", identity.originalFilename());
            row.put("heading", hit.heading());
            row.put("scores", extractScores(hit));
            hitRows.add(row);
        }

        Map<String, Object> modeMap = new LinkedHashMap<>();
        modeMap.put("intent", intent.name());
        modeMap.put("topK", topK);
        modeMap.put("retrievalCore", result.retrievalCore());
        modeMap.put("appliedStrategy", result.appliedStrategy() != null ? result.appliedStrategy().name() : null);
        modeMap.put("effectiveQuery", result.effectiveQuery());
        modeMap.put("subQueries", routeDecision != null ? routeDecision.subQueries() : List.of());
        modeMap.put("filters", result.filters().stream()
                .map(f -> Map.of("field", f.field(), "operator", f.operator().name(), "value", f.value()))
                .toList());
        modeMap.put("hits", hitRows);
        return modeMap;
    }

    private com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext assembleQaContext(List<DocumentSearchHit> candidates) {
        if (tokenCounter != null) {
            return contextBuilder.buildWithTokenBudget(
                    candidates, QA_MAX_SOURCES, QA_TOKEN_BUDGET, tokenCounter, PROVIDER, "qwen2.5:7b-instruct")
                    .context();
        }
        return contextBuilder.buildBalancedWithDiagnostics(candidates, QA_MAX_SOURCES).context();
    }

    private List<Map<String, Object>> seedCorpus(
            Path fixtureDir,
            UUID projectId,
            UUID userId,
            String model,
            String fingerprint,
            UUID generationId) throws Exception {
        JsonNode manifest = JSON.readTree(fixtureDir.resolve("corpus-manifest.json").toFile());
        List<Map<String, Object>> corpusExport = new ArrayList<>();
        for (JsonNode docNode : manifest.path("documents")) {
            String filename = docNode.path("filename").asText();
            String expectedSha256 = docNode.path("sha256").asText();
            byte[] fileBytes = Files.readAllBytes(fixtureDir.resolve(filename));
            assertThat(sha256Hex(fileBytes))
                    .withFailMessage("Corpus SHA-256 mismatch for %s", filename)
                    .isEqualTo(expectedSha256);

            String rawText = new String(fileBytes, StandardCharsets.UTF_8);
            List<DocumentChunk> chunks = chunker.split(rawText);
            assertThat(chunks).isNotEmpty();

            boolean isTemporaryDoc = INJECTION_DOC.equals(filename);
            String initialStatus = isTemporaryDoc ? "FAILED" : "READY";

            UUID documentId = UUID.randomUUID();
            documentIds.put(filename, documentId);
            jdbc.update("""
                    INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                      size_bytes,object_key,status,chunk_count,embedding_provider,embedding_model,
                      embedding_dimension,embedding_fingerprint,uploaded_by,indexed_at)
                    VALUES (?,?,?,?,'text/markdown',?,?,?,?,'OLLAMA',?,?,?, ?,now())
                    """,
                    documentId, projectId, filename, filename, fileBytes.length,
                    "fixture/" + documentId + ".md", initialStatus, chunks.size(),
                    model, EMBEDDING_DIMENSION, fingerprint, userId);

            EmbeddingBatch batch = embeddings.embed(
                    projectId,
                    chunks.stream().map(DocumentChunk::content).toList(),
                    EmbeddingProgressListener.NONE);
            assertThat(batch.vectors()).hasSameSizeAs(chunks);
            assertThat(batch.dimension()).isEqualTo(EMBEDDING_DIMENSION);

            List<Map<String, Object>> chunkExport = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                UUID chunkId = chunk.id() != null ? chunk.id() : UUID.randomUUID();
                String metadata = JSON.writeValueAsString(Map.of(
                        "documentId", documentId.toString(),
                        "chunkNo", chunk.chunkNo(),
                        "filename", filename,
                        "embeddingGeneration", generationId.toString()));
                jdbc.update("""
                        INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,
                          content_hash,token_estimate,metadata,embedding_provider,embedding_model,
                          embedding_dimension,embedding_fingerprint,embedding,generation_id)
                        VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,CAST(? AS vector),?)
                        """,
                        chunkId, projectId, documentId, chunk.chunkNo(), chunk.heading(), chunk.content(),
                        chunk.contentHash(), chunk.tokenEstimate(), metadata,
                        PROVIDER, model, EMBEDDING_DIMENSION, fingerprint,
                        batch.vectors().get(i).toString(), generationId);

                chunkIdentities.put(chunkId, new ChunkIdentity(
                        documentId, chunk.chunkNo(), chunk.contentHash(), filename));
                Map<String, Object> cMap = new LinkedHashMap<>();
                cMap.put("chunkId", chunkId.toString());
                cMap.put("documentId", documentId.toString());
                cMap.put("chunkNo", chunk.chunkNo());
                cMap.put("contentHash", chunk.contentHash());
                cMap.put("heading", chunk.heading());
                cMap.put("chunkType", chunk.chunkType());
                chunkExport.add(cMap);
            }

            Map<String, Object> docExport = new LinkedHashMap<>();
            docExport.put("filename", filename);
            docExport.put("sha256", expectedSha256);
            docExport.put("documentId", documentId.toString());
            docExport.put("temporary", isTemporaryDoc);
            docExport.put("chunks", chunkExport);
            corpusExport.add(docExport);
        }
        return corpusExport;
    }

    private List<JsonNode> loadAllQuestions(Path fixtureDir) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        for (String manifestName : List.of("question-manifest.json", "question-manifest-holdout.json")) {
            JsonNode root = JSON.readTree(fixtureDir.resolve(manifestName).toFile());
            root.path("questions").forEach(all::add);
        }
        return all;
    }

    private UUID seedEmbeddingConfig(String baseUrl, String model, String fingerprint) {
        UUID configId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,
                  batch_size,fingerprint,enabled)
                VALUES (?,'OLLAMA',?,'/v1/embeddings',?,1024,32,?,TRUE)
                """, configId, baseUrl, model, fingerprint);
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'ACTIVE')",
                generationId, configId);
        jdbc.update("UPDATE system_embedding_config SET generation_id=? WHERE id=?", generationId, configId);
        return generationId;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, "golden-" + id.toString().substring(0, 8), "test-only-hash", "GoldenExporter");
        return id;
    }

    private UUID insertProject(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                id, "p2-golden-export", userId, userId);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", id, userId);
        return id;
    }

    private ChunkIdentity resolveIdentity(UUID chunkId, UUID fallbackDocId, String fallbackHash, String fallbackFilename) {
        if (chunkId != null) {
            ChunkIdentity cached = chunkIdentities.get(chunkId);
            if (cached != null) {
                return cached;
            }
            List<ChunkIdentity> fromDb = jdbc.query(
                    """
                    SELECT c.document_id, c.chunk_no, c.content_hash, d.original_filename
                    FROM document_chunk c
                    JOIN project_document d ON d.id = c.document_id
                    WHERE c.id = ?
                    """,
                    (rs, rowNum) -> new ChunkIdentity(
                            rs.getObject("document_id", UUID.class),
                            rs.getInt("chunk_no"),
                            rs.getString("content_hash"),
                            rs.getString("original_filename")),
                    chunkId);
            if (!fromDb.isEmpty()) {
                ChunkIdentity resolved = fromDb.getFirst();
                chunkIdentities.put(chunkId, resolved);
                return resolved;
            }
        }
        return new ChunkIdentity(fallbackDocId, -1, fallbackHash, fallbackFilename);
    }

    private Map<String, Object> extractScores(DocumentSearchHit hit) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object raw = hit.metadata() != null ? hit.metadata().get(RetrievalScores.METADATA_KEY) : null;
        Double dense = null;
        Double lexical = null;
        Double rrf = null;
        Double rerank = null;
        Double admission = hit.similarity();
        if (raw instanceof Map<?, ?> map) {
            dense = toDouble(map.get(RetrievalScores.DENSE_SIMILARITY));
            lexical = toDouble(map.get(RetrievalScores.LEXICAL_SCORE));
            rrf = toDouble(map.get(RetrievalScores.RRF_SCORE));
            rerank = toDouble(map.get(RetrievalScores.RERANK_SCORE));
            Double typedAdmission = toDouble(map.get(RetrievalScores.ADMISSION_SCORE));
            if (typedAdmission != null) {
                admission = typedAdmission;
            }
        }
        Double finalRankingScore = rerank != null ? rerank : (rrf != null ? rrf : admission);
        out.put("dense", dense);
        out.put("lexical", lexical);
        out.put("rrf", rrf);
        out.put("rerank", rerank);
        out.put("admission", admission);
        out.put("final", finalRankingScore);
        return out;
    }

    private Map<String, Object> serializeCandidate(RetrievalCandidate c, boolean includeContent) {
        ChunkIdentity id = resolveIdentity(c.id(), c.documentId(), c.contentHash(), c.originalFilename());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("documentId", id.documentId() != null ? id.documentId().toString() : null);
        row.put("chunkNo", id.chunkNo());
        row.put("contentHash", id.contentHash());
        row.put("originalFilename", id.originalFilename());
        row.put("heading", c.heading());
        if (includeContent) {
            row.put("content", c.effectiveContent());
        }
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("dense", c.denseSimilarity());
        scores.put("lexical", c.lexicalScore());
        scores.put("rrf", c.rrfScore());
        scores.put("rerank", c.rerankScore());
        scores.put("admission", c.admissionScore());
        scores.put("final", c.rerankScore() != null ? c.rerankScore()
                : (c.rrfScore() != null ? c.rrfScore() : c.admissionScore()));
        row.put("scores", scores);
        row.put("denseRank", c.denseRank());
        row.put("lexicalRank", c.lexicalRank());
        return row;
    }

    private static Double toDouble(Object val) {
        return val instanceof Number num ? num.doubleValue() : null;
    }

    private static Path resolveFixtureDir() {
        Path fromModule = Path.of("../tests/fixtures/documents/p9-rag-generalization-01");
        if (Files.isDirectory(fromModule)) {
            return fromModule.normalize();
        }
        Path fromRoot = Path.of("tests/fixtures/documents/p9-rag-generalization-01");
        if (Files.isDirectory(fromRoot)) {
            return fromRoot.normalize();
        }
        throw new IllegalStateException("Golden fixture corpus directory not found");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record ChunkIdentity(UUID documentId, int chunkNo, String contentHash, String originalFilename) {
    }

    private static final class RecordingContext {
        String currentQuestionId;
        String currentMode;
        QueryRouteDecision lastDecision;
        final List<Map<String, Object>> rrfCases = new ArrayList<>();
        final List<Map<String, Object>> rerankerCases = new ArrayList<>();

        void begin(String questionId, String mode) {
            this.currentQuestionId = questionId;
            this.currentMode = mode;
            this.lastDecision = null;
        }

        void end() {
            this.currentQuestionId = null;
            this.currentMode = null;
        }
    }

    private static final class RecordingQueryRouter extends QueryRouter {
        private final QueryRouter delegate;
        private final RecordingContext recorder;

        RecordingQueryRouter(QueryRouter delegate, RecordingContext recorder) {
            this.delegate = delegate;
            this.recorder = recorder;
        }

        @Override
        public QueryRouteDecision route(String rawQuery, List<UUID> documentIds, UUID userId, UUID projectId) {
            QueryRouteDecision decision = delegate.route(rawQuery, documentIds, userId, projectId);
            recorder.lastDecision = decision;
            return decision;
        }
    }

    private final class RecordingFusionStrategy implements FusionStrategy {
        private final ReciprocalRankFusion delegate = new ReciprocalRankFusion();
        private final RecordingContext recorder;

        RecordingFusionStrategy(RecordingContext recorder) {
            this.recorder = recorder;
        }

        @Override
        public List<RetrievalCandidate> fuse(
                List<RetrievalCandidate> denseCandidates,
                List<RetrievalCandidate> lexicalCandidates,
                int topK) {
            List<RetrievalCandidate> output = delegate.fuse(denseCandidates, lexicalCandidates, topK);
            if (recorder.currentQuestionId != null) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("questionId", recorder.currentQuestionId);
                record.put("mode", recorder.currentMode);
                record.put("topK", topK);
                record.put("denseInputs", denseCandidates.stream()
                        .map(c -> serializeCandidate(c, false)).toList());
                record.put("lexicalInputs", lexicalCandidates.stream()
                        .map(c -> serializeCandidate(c, false)).toList());
                record.put("output", output.stream()
                        .map(c -> serializeCandidate(c, false)).toList());
                recorder.rrfCases.add(record);
            }
            return output;
        }
    }

    private final class RecordingHeuristicReranker extends SemanticHeuristicReranker {
        private final RecordingContext recorder;

        RecordingHeuristicReranker(RecordingContext recorder) {
            this.recorder = recorder;
        }

        @Override
        public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
            List<RetrievalCandidate> output = super.rerank(query, candidates, topN);
            if (recorder.currentQuestionId != null) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("questionId", recorder.currentQuestionId);
                record.put("mode", recorder.currentMode);
                record.put("query", query);
                record.put("topN", topN);
                record.put("inputs", candidates.stream()
                        .map(c -> serializeCandidate(c, true)).toList());
                record.put("output", output.stream()
                        .map(c -> serializeCandidate(c, false)).toList());
                recorder.rerankerCases.add(record);
            }
            return output;
        }
    }
}
