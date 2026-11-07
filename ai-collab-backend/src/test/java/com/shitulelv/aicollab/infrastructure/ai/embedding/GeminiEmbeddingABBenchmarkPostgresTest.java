package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.shitulelv.aicollab.common.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG_V2_GEMINI_ADAPTIVE_RETRIEVAL-01 阶段 2｜Gemini Embedding A/B 检索评测（受控门控）。
 *
 * <p>协议（与公开 Benchmark 的关系）：
 * <ul>
 *   <li>语料：{@code CUSTOM_CORPUS_SUBSET}——官方 DuRetrieval corpus 的固定子集（seed=20261107）：
 *       300 冻结查询的全部相关文档并集 + 随机负样本补足至 5,000 篇。<b>不得标记为完整官方 Benchmark</b>；
 *       完整 10 万语料不外发免费 API（出站边界）。查询沿用冻结的 300 题与官方 qrels。</li>
 *   <li>对照：同一子集分别以 Ollama qwen3-embedding:0.6b（1024 维，本地）与
 *       Gemini gemini-embedding-001（1536 维，RETRIEVAL_DOCUMENT/RETRIEVAL_QUERY 任务配对，免费 API）
 *       建立两个<b>完全隔离</b>的索引（独立 project/generation/fingerprint/维度）；
 *       同一查询、同一 Dense-only 检索预算（topK=10，与公开 Benchmark 池深 10 口径一致）。</li>
 *   <li>隔离保证：检索 SQL 按 provider/model/dimension/fingerprint/generationId 过滤，
 *       两代向量空间物理隔离；不触碰生产知识库与生产数据库。</li>
 *   <li>指标：Recall@5/@10、MRR@10、nDCG@10（文档级二值相关）、查询嵌入 P50、检索总 P50/P95、
 *       429/超时计数、索引请求与 token 成本。逐题原始记录全量落盘。</li>
 * </ul>
 * 门控：{@code AI_GEMINI_EMBED_AB=true} + {@code GEMINI_API_KEY}；输出 target/gemini-embed-ab/。</p>
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
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms",
        "spring.data.redis.timeout=100ms",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket"
})
@EnabledIfEnvironmentVariable(named = "AI_GEMINI_EMBED_AB", matches = "true")
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GeminiEmbeddingABBenchmarkPostgresTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String QWEN_MODEL = "qwen3-embedding:0.6b";
    private static final int QWEN_DIM = 1024;
    private static final String GEMINI_MODEL = "gemini-embedding-001";
    private static final int GEMINI_DIM = 1536;
    private static final int SUBSET_SIZE = 5000;
    private static final long SEED = 20261107L;
    private static final String DOC_PREFIX = "duretrieval/";
    private static final int EVAL_TOP_K = 10;

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final Path SAMPLE_MANIFEST = REPO.resolve(
            "docs/benchmark/2026-11-07-rag-v2-public-benchmark/sample-manifest.json");
    private static final Path CACHE = Path.of(System.getenv()
            .getOrDefault("AI_DU_BENCH_CACHE", "E:/project/ai-collab-benchmark-cache"));
    private static final Path CORPUS_JSONL = CACHE.resolve("duretrieval/corpus.jsonl");
    private static final Path OUTPUT = REPO.resolve("ai-collab-backend/target/gemini-embed-ab");

    private static final String PROJECT_QWEN = "AB 子集索引 Qwen (CUSTOM_CORPUS_SUBSET)";
    private static final String PROJECT_GEMINI = "AB 子集索引 Gemini (CUSTOM_CORPUS_SUBSET)";

    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectEmbeddingGateway gateway;
    @Autowired DocumentChunker chunker;
    @Autowired DocumentRepository documents;
    @Autowired com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher cipher;

    @MockitoBean
    com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob taskPlanRecoveryJob;

    private UUID user;
    private ProjectEmbeddingConfig qwenConfig;
    private ProjectEmbeddingConfig geminiConfig;
    private String qwenFingerprint;
    private String geminiFingerprint;
    private UUID qwenGeneration;
    private UUID geminiGeneration;
    private UUID qwenProject;
    private UUID geminiProject;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("AI_DU_BENCH_DB_URL");
        registry.add("spring.datasource.url", () -> Objects.requireNonNull(url,
                "必须提供 AI_DU_BENCH_DB_URL（隔离 Benchmark 库）"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AI_DU_BENCH_DB_USER", "bench"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AI_DU_BENCH_DB_PASSWORD", "bench"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("model.config.master-key", () -> {
            try {
                for (String line : Files.readAllLines(REPO.resolve(".env"))) {
                    if (line.startsWith("MODEL_CONFIG_MASTER_KEY=")) {
                        String value = line.substring(line.indexOf('=') + 1).strip();
                        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                        return value;
                    }
                }
            } catch (Exception ignored) { /* fail closed without printing configuration */ }
            throw new IllegalStateException("Local master key unavailable");
        });
    }

    private String geminiApiKey() {
        String key = System.getenv("GEMINI_API_KEY");
        assertThat(key).as("GEMINI_API_KEY 必须提供").isNotBlank();
        return key.strip();
    }

    // ==================== 阶段一：冻结子集与双索引构建 ====================

    @Test
    @Order(1)
    void buildSubsetAndDualIndex() throws Exception {
        user = UUID.randomUUID();
        prepareContext();
        qwenConfig = new ProjectEmbeddingConfig(UUID.randomUUID(), "OLLAMA",
                "http://127.0.0.1:11434", "/v1/embeddings", null, QWEN_MODEL, QWEN_DIM, 64,
                true, OffsetDateTime.now(), OffsetDateTime.now());
        String geminiKey = cipher.encrypt(geminiApiKey());
        geminiConfig = new ProjectEmbeddingConfig(UUID.randomUUID(), "GEMINI",
                "https://generativelanguage.googleapis.com", "/v1beta", geminiKey, GEMINI_MODEL, GEMINI_DIM, 50,
                true, OffsetDateTime.now(), OffsetDateTime.now());
        qwenFingerprint = EmbeddingFingerprints.fingerprint("OLLAMA", QWEN_MODEL, QWEN_DIM);
        geminiFingerprint = EmbeddingFingerprints.geminiFingerprint(GEMINI_MODEL,
                "https://generativelanguage.googleapis.com", "/v1beta", GEMINI_DIM, "RETRIEVAL_DOCUMENT/RETRIEVAL_QUERY");
        // generation_id 有 FK 约束：按指纹幂等注册 config + generation 行（enabled=false 不参与运行时路由）
        ensureConfigAndGeneration(qwenFingerprint, "OLLAMA", "http://127.0.0.1:11434", "/v1/embeddings",
                QWEN_MODEL, 1024, 64);
        ensureConfigAndGeneration(geminiFingerprint, "GEMINI", "https://generativelanguage.googleapis.com",
                "/v1beta", GEMINI_MODEL, 1536, 100);

        // ---- 冻结子集计算（可复现：seed + 相关文档并集 + 随机补足）----
        JsonNode manifest = JSON.readTree(SAMPLE_MANIFEST.toFile());
        Set<String> relevantPids = new LinkedHashSet<>();
        Map<String, String> queryByQid = new LinkedHashMap<>();
        for (JsonNode q : manifest.path("duretrieval").path("samples")) {
            queryByQid.put(q.path("qid").asText(), q.path("query").asText());
            q.path("relevantPids").forEach(p -> relevantPids.add(p.asText()));
        }
        Map<String, String> corpusByPid = new LinkedHashMap<>();
        try (var reader = Files.newBufferedReader(CORPUS_JSONL, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode row = JSON.readTree(line);
                corpusByPid.putIfAbsent(row.path("id").asText(), row.path("text").asText());
            }
        }
        // 相关文档必须全部存在于 corpus
        List<String> missing = relevantPids.stream().filter(p -> !corpusByPid.containsKey(p)).toList();
        assertThat(missing).as("相关文档必须全部在官方 corpus 中").isEmpty();

        Random rng = new Random(SEED);
        List<String> allPids = new ArrayList<>(corpusByPid.keySet());
        Collections.shuffle(allPids, rng);
        LinkedHashSet<String> subset = new LinkedHashSet<>(relevantPids);
        for (String pid : allPids) {
            if (subset.size() >= SUBSET_SIZE) break;
            subset.add(pid);
        }
        List<String> subsetList = new ArrayList<>(subset);
        Collections.shuffle(subsetList, rng); // 索引顺序随机化

        long relevantTokens = relevantPids.stream().mapToLong(p -> corpusByPid.get(p).length()).sum();
        Map<String, Object> subsetSummary = new LinkedHashMap<>();
        subsetSummary.put("corpusMode", "CUSTOM_CORPUS_SUBSET");
        subsetSummary.put("seed", SEED);
        subsetSummary.put("subsetSize", subsetList.size());
        subsetSummary.put("relevantUnion", relevantPids.size());
        subsetSummary.put("queries", queryByQid.size());
        subsetSummary.put("totalChars", subsetList.stream().mapToLong(p -> corpusByPid.get(p).length()).sum());
        subsetSummary.put("estimatedInputTokensGemini", Math.round(
                subsetList.stream().mapToLong(p -> corpusByPid.get(p).length()).sum() * 0.6));
        subsetSummary.put("geminiApiRequests", (int) Math.ceil(subsetList.size() / 100.0));
        subsetSummary.put("note", "完整官方 10 万语料不外发免费 API；本子集仅供 A/B 对照，不得标记为完整官方 Benchmark");

        // ---- 双索引构建（checkpoint 续跑）----
        UUID user0 = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE display_name='GeminiEmbeddingAB' LIMIT 1", UUID.class);
        qwenProject = jdbc.queryForObject("SELECT id FROM project WHERE name=?", UUID.class, PROJECT_QWEN);
        geminiProject = jdbc.queryForObject("SELECT id FROM project WHERE name=?", UUID.class, PROJECT_GEMINI);
        Set<String> qwenDone = existingDocs(qwenProject);
        Set<String> geminiDone = existingDocs(geminiProject);

        int qwenNew = 0, geminiNew = 0;
        long geminiRequests = 0, gemini429 = 0, qwenChars = 0, geminiChars = 0;
        List<String[]> pendingQwen = new ArrayList<>();
        List<String[]> pendingGemini = new ArrayList<>();
        for (String[] pidText : subsetList.stream().map(p -> new String[]{p, corpusByPid.get(p)}).toList()) {
            if (!qwenDone.contains(pidText[0])) {
                pendingQwen.add(pidText);
                if (pendingQwen.size() >= 64) {
                    qwenNew += flushIndex(pendingQwen, qwenProject, qwenGeneration, qwenFingerprint,
                            "OLLAMA", QWEN_MODEL, QWEN_DIM, null, pendingQwen.size());
                    pendingQwen.clear();
                }
            }
            if (!geminiDone.contains(pidText[0])) {
                pendingGemini.add(pidText);
                if (pendingGemini.size() >= 50) {
                    geminiNew += flushIndex(pendingGemini, geminiProject, geminiGeneration, geminiFingerprint,
                            "GEMINI", GEMINI_MODEL, GEMINI_DIM, geminiApiKey(), 50);
                    geminiRequests++;
                    geminiChars += pendingGemini.stream().mapToLong(x -> x[1].length()).sum();
                    pendingGemini.clear();
                    Thread.sleep(27_000L); // 免费层 TPM≈30k 节流：50 篇≈13.5k tokens/27s
                }
            }
        }
        if (!pendingQwen.isEmpty()) {
            qwenNew += flushIndex(pendingQwen, qwenProject, qwenGeneration, qwenFingerprint,
                    "OLLAMA", QWEN_MODEL, QWEN_DIM, null, pendingQwen.size());
            pendingQwen.clear();
        }
        while (!pendingGemini.isEmpty()) {
            List<String[]> slice = pendingGemini.subList(0, Math.min(50, pendingGemini.size()));
            List<String[]> sliceCopy = new ArrayList<>(slice);
            geminiNew += flushIndex(sliceCopy, geminiProject, geminiGeneration, geminiFingerprint,
                    "GEMINI", GEMINI_MODEL, GEMINI_DIM, geminiApiKey(), 50);
            geminiRequests++;
            geminiChars += sliceCopy.stream().mapToLong(x -> x[1].length()).sum();
            slice.clear();
            Thread.sleep(27_000L);
        }

        Long qwenDocs = jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE project_id=? AND status='READY'", Long.class, qwenProject);
        Long geminiDocs = jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE project_id=? AND status='READY'", Long.class, geminiProject);
        subsetSummary.put("qwenIndexedDocuments", qwenDocs);
        subsetSummary.put("geminiIndexedDocuments", geminiDocs);
        subsetSummary.put("qwenNewThisRun", qwenNew);
        subsetSummary.put("geminiNewThisRun", geminiNew);
        subsetSummary.put("geminiApiRequestsThisRun", geminiRequests);
        subsetSummary.put("gemini429ThisRun", gemini429);
        Files.createDirectories(OUTPUT);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                OUTPUT.resolve("ab-subset-and-index-summary.json").toFile(), subsetSummary);
        System.out.println("GEMINI_AB_INDEX " + subsetSummary);
    }

    private void ensureConfigAndGeneration(String fingerprint, String provider, String baseUrl,
            String apiPath, String model, int dimensions, int batchSize) {
        List<UUID> existing = jdbc.queryForList(
                "SELECT id FROM system_embedding_config WHERE fingerprint=? LIMIT 1", UUID.class, fingerprint);
        UUID configId;
        if (existing.isEmpty()) {
            configId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,
                      batch_size,fingerprint,enabled)
                    VALUES (?,?,?,?,?,?,?,?,FALSE)
                    """, configId, provider, baseUrl, apiPath, model, dimensions, batchSize, fingerprint);
        } else {
            configId = existing.getFirst();
        }
        List<UUID> generations = jdbc.queryForList(
                "SELECT id FROM embedding_index_generation WHERE config_id=? LIMIT 1", UUID.class, configId);
        UUID generation = generations.isEmpty() ? UUID.randomUUID() : generations.getFirst();
        if (generations.isEmpty()) {
            jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'ACTIVE')",
                    generation, configId);
        }
        if ("OLLAMA".equals(provider)) qwenGeneration = generation; else geminiGeneration = generation;
    }

    private UUID ensureGeneration(UUID configId) {
        List<UUID> existing = jdbc.queryForList(
                "SELECT id FROM embedding_index_generation WHERE config_id=?", UUID.class, configId);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID generation = UUID.randomUUID();
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'ACTIVE')",
                generation, configId);
        return generation;
    }

    private Set<String> existingDocs(UUID project) {
        // 返回裸 pid（original_filename 带 duretrieval/ 前缀，与跳过检查的 pid 对齐）
        return jdbc.queryForList(
                "SELECT original_filename FROM project_document WHERE project_id=? AND status='READY'",
                String.class, project).stream()
                .map(f -> f.startsWith(DOC_PREFIX) ? f.substring(DOC_PREFIX.length()) : f)
                .collect(Collectors.toSet());
    }

    /** 切块→嵌入→落库；返回新增 chunk 数。 */
    private long flushIndex(List<String[]> batch, UUID project, UUID generation, String fingerprint,
            String provider, String model, int dimension, String rawApiKey, int apiBatchSize) throws Exception {
        List<String[]> passageChunks = new ArrayList<>(); // {pid, chunkNo, content, heading, hash, tokenEstimate, metadataJson}
        for (String[] passage : batch) {
            var chunks = chunker.split(passage[1]);
            for (var chunk : chunks) {
                passageChunks.add(new String[]{passage[0], String.valueOf(chunk.chunkNo()), chunk.content(),
                        chunk.heading(), chunk.contentHash(), String.valueOf(chunk.tokenEstimate()),
                        JSON.writeValueAsString(chunk.metadata())});
            }
        }
        if (passageChunks.isEmpty()) return 0;
        EmbeddingBatch vectors = embedWithQuotaRetry(provider, model,
                passageChunks.stream().map(c -> c[2]).toList(), rawApiKey, apiBatchSize);

        Map<String, Integer> chunkCountByPid = new LinkedHashMap<>();
        Map<String, Long> sizeByPid = new LinkedHashMap<>();
        for (String[] c : passageChunks) {
            chunkCountByPid.merge(c[0], 1, Integer::sum);
            sizeByPid.merge(c[0], (long) c[2].getBytes(StandardCharsets.UTF_8).length, Long::sum);
        }
        List<Object[]> docRows = new ArrayList<>();
        Map<String, UUID> docIds = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : chunkCountByPid.entrySet()) {
            UUID docId = UUID.randomUUID();
            docIds.put(e.getKey(), docId);
            // object_key 全局唯一：加 project 前缀隔离双索引
            docRows.add(new Object[]{docId, project, e.getKey(), DOC_PREFIX + e.getKey(), "text/plain",
                    sizeByPid.get(e.getKey()), project + "/duretrieval/" + e.getKey(), e.getValue(),
                    provider, model, dimension, fingerprint, user});
        }
        jdbc.batchUpdate("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,status,chunk_count,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,uploaded_by,indexed_at)
                VALUES (?,?,?,?,?,?,?,'READY',?,?,?,?,?,?,now())
                """, docRows);
        List<Object[]> chunkRows = new ArrayList<>();
        for (int i = 0; i < passageChunks.size(); i++) {
            String[] c = passageChunks.get(i);
            chunkRows.add(new Object[]{UUID.randomUUID(), project, docIds.get(c[0]),
                    Integer.parseInt(c[1]), c[3], c[2], c[4], Integer.parseInt(c[5]), c[6],
                    provider, model, dimension, fingerprint, vectors.vectors().get(i).toString(), generation});
        }
        jdbc.batchUpdate("""
                INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,
                  content_hash,token_estimate,metadata,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,embedding,generation_id)
                VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,CAST(? AS vector),?)
                """, chunkRows);
        return passageChunks.size();
    }

    /** 429 退避重试（免费层限流），最多 3 次、30s 间隔。 */
    private EmbeddingBatch embedWithQuotaRetry(String provider, String model,
            List<String> texts, String rawApiKey, int apiBatchSize) throws Exception {
        ProjectEmbeddingConfig config = "GEMINI".equals(provider)
                ? new ProjectEmbeddingConfig(UUID.randomUUID(), "GEMINI",
                        "https://generativelanguage.googleapis.com", "/v1beta",
                        rawApiKey == null ? null : cipher.encrypt(rawApiKey), model, GEMINI_DIM, apiBatchSize,
                        true, OffsetDateTime.now(), OffsetDateTime.now())
                : new ProjectEmbeddingConfig(UUID.randomUUID(), "OLLAMA",
                        "http://127.0.0.1:11434", "/v1/embeddings", null, model, QWEN_DIM, apiBatchSize,
                        true, OffsetDateTime.now(), OffsetDateTime.now());
        RuntimeException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                return gateway.embedWithConfig(config, texts, EmbeddingProgressListener.NONE, EmbeddingTaskType.DOCUMENT);
            } catch (RuntimeException e) {
                boolean quota = e instanceof BusinessException business
                        && business.getErrorCode() == com.shitulelv.aicollab.common.exception.ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED;
                if (!quota) throw e;
                last = e;
                System.out.println("GEMINI_AB_INDEX_429_BACKOFF attempt=" + attempt);
                Thread.sleep(60_000L);
            }
        }
        throw last;
    }

    // ==================== 阶段二：300 查询 A/B 对照 ====================

    @Test
    @Order(2)
    void runAbEvaluation() throws Exception {
        prepareContext();
        qwenProject = jdbc.queryForObject("SELECT id FROM project WHERE name=?", UUID.class, PROJECT_QWEN);
        geminiProject = jdbc.queryForObject("SELECT id FROM project WHERE name=?", UUID.class, PROJECT_GEMINI);
        qwenGeneration = jdbc.queryForObject(
                "SELECT DISTINCT generation_id FROM document_chunk c JOIN project_document d ON d.id=c.document_id "
                        + "WHERE d.project_id=? LIMIT 1", UUID.class, qwenProject);
        geminiGeneration = jdbc.queryForObject(
                "SELECT DISTINCT generation_id FROM document_chunk c JOIN project_document d ON d.id=c.document_id "
                        + "WHERE d.project_id=? LIMIT 1", UUID.class, geminiProject);
        qwenFingerprint = jdbc.queryForObject(
                "SELECT DISTINCT c.embedding_fingerprint FROM document_chunk c JOIN project_document d ON d.id=c.document_id "
                        + "WHERE d.project_id=? LIMIT 1", String.class, qwenProject);
        geminiFingerprint = jdbc.queryForObject(
                "SELECT DISTINCT c.embedding_fingerprint FROM document_chunk c JOIN project_document d ON d.id=c.document_id "
                        + "WHERE d.project_id=? LIMIT 1", String.class, geminiProject);

        JsonNode manifest = JSON.readTree(SAMPLE_MANIFEST.toFile());
        List<JsonNode> queries = new ArrayList<>();
        manifest.path("duretrieval").path("samples").forEach(queries::add);
        assertThat(queries).hasSize(300);

        Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>();
        records.put("QWEN_1024", new ArrayList<>());
        records.put("GEMINI_1536", new ArrayList<>());
        Map<String, List<Long>> latencies = new LinkedHashMap<>();
        latencies.put("QWEN_1024", new ArrayList<>());
        latencies.put("GEMINI_1536", new ArrayList<>());
        Map<String, Long> embedLatencies = new LinkedHashMap<>();
        embedLatencies.put("QWEN_1024", 0L);
        embedLatencies.put("GEMINI_1536", 0L);
        int[] quotaHits = {0};

        int done = 0;
        for (JsonNode q : queries) {
            String qid = q.path("qid").asText();
            String query = q.path("query").asText();
            Set<String> relevant = new HashSet<>();
            q.path("relevantPids").forEach(p -> relevant.add(p.asText()));

            for (String side : List.of("QWEN_1024", "GEMINI_1536")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("qid", qid);
                row.put("variant", side);
                try {
                    UUID project = "QWEN_1024".equals(side) ? qwenProject : geminiProject;
                    UUID generation = "QWEN_1024".equals(side) ? qwenGeneration : geminiGeneration;
                    String fingerprint = "QWEN_1024".equals(side) ? qwenFingerprint : geminiFingerprint;
                    String model = "QWEN_1024".equals(side) ? QWEN_MODEL : GEMINI_MODEL;
                    String provider = "QWEN_1024".equals(side) ? "OLLAMA" : "GEMINI";
                    int dimension = "QWEN_1024".equals(side) ? QWEN_DIM : GEMINI_DIM;

                    long started = System.nanoTime();
                    EmbeddingBatch queryEmbedding = embedQueryWithQuotaRetry(provider, model, query,
                            dimension, fingerprint, quotaHits);
                    long embedMs = (System.nanoTime() - started) / 1_000_000L;
                    embedLatencies.merge(side, embedMs, Long::sum);

                    long searchStarted = System.nanoTime();
                    List<DocumentSearchHit> hits = documents.search(project, queryEmbedding.vectors().getFirst(),
                            provider, model, dimension, fingerprint, null, EVAL_TOP_K, generation);
                    long searchMs = (System.nanoTime() - searchStarted) / 1_000_000L;
                    long totalMs = (System.nanoTime() - started) / 1_000_000L;
                    latencies.get(side).add(totalMs);

                    List<String> ranked = hits.stream()
                            .map(DocumentSearchHit::originalFilename)
                            .filter(Objects::nonNull)
                            .map(f -> f.startsWith(DOC_PREFIX) ? f.substring(DOC_PREFIX.length()) : f)
                            .distinct().toList();
                    List<String> top10 = ranked.stream().limit(EVAL_TOP_K).toList();
                    int hitsAt5 = (int) top10.stream().limit(5).filter(relevant::contains).count();
                    int hitsAt10 = (int) top10.stream().filter(relevant::contains).count();
                    int firstHit = 0;
                    for (int i = 0; i < top10.size(); i++) {
                        if (relevant.contains(top10.get(i))) { firstHit = i + 1; break; }
                    }
                    double dcg = 0;
                    for (int i = 0; i < top10.size(); i++) {
                        if (relevant.contains(top10.get(i))) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
                    }
                    double idcg = 0;
                    for (int i = 0; i < Math.min(relevant.size(), 10); i++) idcg += 1.0 / (Math.log(i + 2) / Math.log(2));

                    row.put("rankedDocIds", top10);
                    row.put("hitsAt5", hitsAt5);
                    row.put("hitsAt10", hitsAt10);
                    row.put("relevantCount", relevant.size());
                    row.put("reciprocalRank", firstHit > 0 ? 1.0 / firstHit : 0.0);
                    row.put("ndcg", idcg > 0 ? dcg / idcg : 0.0);
                    row.put("successAt10", hitsAt10 > 0);
                    row.put("embedMs", embedMs);
                    row.put("searchMs", searchMs);
                    row.put("latencyMs", totalMs);
                } catch (Exception e) {
                    row.put("failed", true);
                    row.put("failureReason", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                records.get(side).add(row);
            }
            done++;
            Thread.sleep(1200L); // 免费层 RPM 节流（每题两侧）
            if (done % 25 == 0) System.out.println("GEMINI_AB_EVAL_PROGRESS " + done + "/300");
        }

        // 汇总
        Map<String, Map<String, Object>> summary = new LinkedHashMap<>();
        for (String side : List.of("QWEN_1024", "GEMINI_1536")) {
            List<Map<String, Object>> rows = records.get(side);
            List<Map<String, Object>> ok = rows.stream().filter(r -> !r.containsKey("failed")).toList();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("evaluatedQueries", ok.size());
            s.put("failedQueries", rows.size() - ok.size());
            if (!ok.isEmpty()) {
                s.put("recallAt5", avg(ok, r -> (int) r.get("hitsAt5") / (double) (int) r.get("relevantCount")));
                s.put("recallAt10", avg(ok, r -> (int) r.get("hitsAt10") / (double) (int) r.get("relevantCount")));
                s.put("mrrAt10", avg(ok, r -> (double) r.get("reciprocalRank")));
                s.put("ndcgAt10", avg(ok, r -> (double) r.get("ndcg")));
                s.put("precisionAt5", avg(ok, r -> (int) r.get("hitsAt5") / 5.0));
                s.put("successRateAt10", avg(ok, r -> (Boolean) r.get("successAt10") ? 1.0 : 0.0));
                List<Long> lat = new ArrayList<>(latencies.get(side));
                Collections.sort(lat);
                s.put("latencyP50Ms", percentile(lat, 50));
                s.put("latencyP95Ms", percentile(lat, 95));
                s.put("queryEmbeddingTotalMs", embedLatencies.get(side));
                s.put("queryEmbeddingAvgMs", embedLatencies.get(side) / (double) ok.size());
            }
            summary.put(side, s);
        }
        summary.get("QWEN_1024").put("corpusMode", "CUSTOM_CORPUS_SUBSET (5,000 docs, seed 20261107)");
        summary.get("GEMINI_1536").put("corpusMode", "CUSTOM_CORPUS_SUBSET (5,000 docs, seed 20261107)");
        summary.get("GEMINI_1536").put("quota429Hits", quotaHits[0]);

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("benchmark", "DuRetrieval CUSTOM_CORPUS_SUBSET A/B (Qwen 1024 vs Gemini 1536, Dense-only topK=10)");
        results.put("variantSummary", summary);
        results.put("records", records);
        Files.createDirectories(OUTPUT);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                OUTPUT.resolve("ab-retrieval-results.json").toFile(), results);

        StringBuilder csv = new StringBuilder("variant,evaluatedQueries,recallAt5,recallAt10,mrrAt10,ndcgAt10,precisionAt5,successRateAt10,latencyP50Ms,latencyP95Ms,queryEmbeddingAvgMs\n");
        for (String side : List.of("QWEN_1024", "GEMINI_1536")) {
            Map<String, Object> s = summary.get(side);
            csv.append(side).append(',').append(s.get("evaluatedQueries")).append(',')
                    .append(fmt(s.get("recallAt5"))).append(',').append(fmt(s.get("recallAt10"))).append(',')
                    .append(fmt(s.get("mrrAt10"))).append(',').append(fmt(s.get("ndcgAt10"))).append(',')
                    .append(fmt(s.get("precisionAt5"))).append(',').append(fmt(s.get("successRateAt10"))).append(',')
                    .append(s.get("latencyP50Ms")).append(',').append(s.get("latencyP95Ms")).append(',')
                    .append(fmt(s.get("queryEmbeddingAvgMs"))).append('\n');
        }
        Files.writeString(OUTPUT.resolve("ab-summary.csv"), csv.toString(), StandardCharsets.UTF_8);
        System.out.println("GEMINI_AB_RESULTS=" + OUTPUT.toAbsolutePath());
        System.out.println(csv);
    }

    private EmbeddingBatch embedQueryWithQuotaRetry(String provider, String model, String query,
            int dimension, String fingerprint, int[] quotaHits) throws Exception {
        ProjectEmbeddingConfig config = "GEMINI".equals(provider)
                ? new ProjectEmbeddingConfig(UUID.randomUUID(), "GEMINI",
                        "https://generativelanguage.googleapis.com", "/v1beta",
                        cipher.encrypt(geminiApiKey()), model, dimension, 8,
                        true, OffsetDateTime.now(), OffsetDateTime.now())
                : new ProjectEmbeddingConfig(UUID.randomUUID(), "OLLAMA",
                        "http://127.0.0.1:11434", "/v1/embeddings", null, model, dimension, 8,
                        true, OffsetDateTime.now(), OffsetDateTime.now());
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return gateway.embedWithConfig(config, List.of(query), EmbeddingProgressListener.NONE, EmbeddingTaskType.QUERY);
            } catch (RuntimeException e) {
                boolean quota = e instanceof BusinessException business
                        && business.getErrorCode() == com.shitulelv.aicollab.common.exception.ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED;
                if (!quota) throw e;
                quotaHits[0]++;
                last = e;
                Thread.sleep(30_000L);
            }
        }
        throw last;
    }

    private void prepareContext() {
        // 幂等：跨运行/跨测试方法复用既有 user 与双索引 project
        List<UUID> users = jdbc.queryForList(
                "SELECT id FROM app_user WHERE display_name='GeminiEmbeddingAB'", UUID.class);
        if (users.isEmpty()) {
            user = UUID.randomUUID();
            jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                    user, "gemini-ab-" + user.toString().substring(0, 8), "test-only-hash", "GeminiEmbeddingAB");
        } else {
            user = users.getFirst();
        }
        qwenProject = ensureProject(PROJECT_QWEN);
        geminiProject = ensureProject(PROJECT_GEMINI);
    }

    private UUID ensureProject(String name) {
        List<UUID> existing = jdbc.queryForList("SELECT id FROM project WHERE name=?", UUID.class, name);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                projectId, name, user, user);
        return projectId;
    }

    private static double avg(List<Map<String, Object>> rows, java.util.function.Function<Map<String, Object>, Double> fn) {
        return rows.stream().mapToDouble(fn::apply).average().orElse(0);
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p / 100.0) - 1);
        return sorted.get(Math.max(0, index));
    }

    private static String fmt(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
