package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.retrieval.pipeline.DenseRetriever;
import com.shitulelv.aicollab.retrieval.pipeline.LexicalRetriever;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalResult;
import com.shitulelv.aicollab.retrieval.pipeline.RerankerType;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG_V2_PUBLIC_BENCHMARK-01｜Benchmark A：C-MTEB DuRetrieval 独立第三方检索评测。
 *
 * <p>协议（与生产 RAG V2 的关系）：
 * <ul>
 *   <li>语料：官方完整 corpus（100,001 条，{@code OFFICIAL_CORPUS_FULL}），每条 passage 作为一个独立文档
 *       进入真实 {@link DocumentChunker}（短文本产生单 STANDARD 块，长文本产生 parent/child 块），
 *       经真实 {@link ProjectEmbeddingGateway}（本地 Ollama qwen3-embedding:0.6b，1024 维，指纹冻结）
 *       写入隔离 PostgreSQL（pgvector + pg_trgm），Flyway 全量迁移。</li>
 *   <li>文档身份映射：{@code original_filename = "duretrieval/<pid>"}，候选 {@code RetrievalCandidate.originalFilename}
 *       直接还原公开文档 ID；同一 passage 的多个 chunk（长文）按首次出现去重，不重复计分。</li>
 *   <li>变体：A1 Dense Only / A2 Lexical Only（直接调用生产 Retriever）；
 *       A3 Dense+Lexical+RRF（PASS_THROUGH、无扩展）；A4=A3+句子窗口；A5=A3+父子回读；
 *       A6=RAG V2 Full Default（启发式重排+窗口+父子）。六变体共用同一冻结语料、查询与 Embedding 模型。</li>
 *   <li>指标（文档级，qrels score 全为 1，二值相关）：Recall@5/@10、MRR@10、nDCG@10、Precision@5、
 *       成功率@10、P50/P95 延迟、候选数。逐题原始记录全部落盘（含未命中与负收益）。</li>
 * </ul>
 * 输出仅写 {@code target/du-benchmark/}；整类由 {@code AI_DU_BENCHMARK=true} 显式门控；
 * 隔离库连接由 {@code AI_DU_BENCH_DB_URL/USER/PASSWORD} 提供，绝不触碰生产数据库。</p>
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
@EnabledIfEnvironmentVariable(named = "AI_DU_BENCHMARK", matches = "true")
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DuRetrievalBenchmarkPostgresTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String PROVIDER = "OLLAMA";
    private static final String EMBEDDING_MODEL = "qwen3-embedding:0.6b";
    private static final String FINGERPRINT = EmbeddingFingerprints.fingerprint(PROVIDER, EMBEDDING_MODEL, 1024);
    private static final String DOC_PREFIX = "duretrieval/";
    private static final int EVAL_TOP_K = 10;
    private static final int CANDIDATE_TOP_K = 20;

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    /** 冻结抽样清单（入库到仓库的 sample-manifest.json）。 */
    private static final Path SAMPLE_MANIFEST = REPO.resolve(
            "docs/benchmark/2026-11-07-rag-v2-public-benchmark/sample-manifest.json");
    /** 第三方原始语料缓存目录（仓库外，不入 Git）。 */
    private static final Path CACHE = Path.of(System.getenv()
            .getOrDefault("AI_DU_BENCH_CACHE", "E:/project/ai-collab-benchmark-cache"));
    private static final Path CORPUS_JSONL = CACHE.resolve("duretrieval/corpus.jsonl");
    /** 检索池深：evaluatedVariants 按 AI_DU_BENCH_POOL 切换（10=公平同预算对照，20=扩展池参考组），
     *  影响直接检索器 topK 与管线 finalTopN，保证各变体输出同等数量 chunk 候选后再做文档去重。 */
    private static final int POOL =
            Integer.parseInt(System.getenv().getOrDefault("AI_DU_BENCH_POOL", "10"));
    private static final Path OUTPUT = REPO.resolve("ai-collab-backend/target/du-benchmark");
    private static final Path RESULT_FILE = OUTPUT.resolve(
            POOL == 10 ? "retrieval-results.json" : "retrieval-results-pool" + POOL + ".json");
    private static final Path SUMMARY_FILE = OUTPUT.resolve(
            POOL == 10 ? "retrieval-summary.csv" : "retrieval-summary-pool" + POOL + ".csv");

    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectEmbeddingGateway embeddings;
    @Autowired DocumentChunker chunker;
    @Autowired DenseRetriever denseRetriever;
    @Autowired LexicalRetriever lexicalRetriever;
    @Autowired RetrievalPipeline pipeline;

    @MockitoBean
    TaskPlanRecoveryJob taskPlanRecoveryJob;

    private UUID user;
    private UUID project;
    private UUID generation;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("AI_DU_BENCH_DB_URL");
        registry.add("spring.datasource.url", () -> Objects.requireNonNull(url,
                "必须提供 AI_DU_BENCH_DB_URL（隔离 Benchmark 库，不得指向生产/开发库）"));
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

    // ==================== 阶段一：官方全量语料索引 ====================

    @Test
    @Order(1)
    void indexOfficialCorpus() throws Exception {
        assertThat(SAMPLE_MANIFEST).as("冻结抽样清单必须存在").exists();

        user = UUID.randomUUID();
        project = UUID.randomUUID();
        generation = UUID.randomUUID();

        boolean reindex = "true".equalsIgnoreCase(System.getenv().getOrDefault("AI_DU_BENCH_REINDEX", "false"));
        Long existingDocs = jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE original_filename LIKE 'duretrieval/%'", Long.class);
        if (reindex || existingDocs == null || existingDocs == 0) {
            jdbc.execute("""
                    TRUNCATE document_chunk, project_document, project_member, project, app_user,
                             embedding_index_generation, system_embedding_config CASCADE
                    """);
            existingDocs = 0L;
        }
        boolean freshSchema = existingDocs == 0;

        if (freshSchema) {
            jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                    user, "du-bench-" + user.toString().substring(0, 8), "test-only-hash", "DuRetrievalBenchmark");
            jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                    project, "DuRetrieval 公开基准评测", user, user);
            jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", project, user);
            UUID configId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,
                      batch_size,fingerprint,enabled)
                    VALUES (?,'OLLAMA','http://127.0.0.1:11434','/v1/embeddings',?,1024,32,?,TRUE)
                    """, configId, EMBEDDING_MODEL, FINGERPRINT);
            jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'ACTIVE')", generation, configId);
            jdbc.update("UPDATE system_embedding_config SET generation_id=? WHERE id=?", generation, configId);
        } else {
            // 断点续跑：恢复既有 project/generation
            project = jdbc.queryForObject(
                    "SELECT id FROM project WHERE name='DuRetrieval 公开基准评测' LIMIT 1", UUID.class);
            user = jdbc.queryForObject("SELECT owner_id FROM project WHERE id=?", UUID.class, project);
            generation = jdbc.queryForObject(
                    "SELECT g.id FROM embedding_index_generation g JOIN system_embedding_config c ON c.generation_id=g.id "
                            + "WHERE c.enabled AND g.state='ACTIVE' LIMIT 1", UUID.class);
        }

        Set<String> indexedFilenames = new HashSet<>(jdbc.queryForList(
                "SELECT original_filename FROM project_document WHERE status='READY' AND original_filename LIKE 'duretrieval/%'",
                String.class));
        // 官方 corpus 存在重复 id 行（100,001 行非唯一），跨批次去重保护
        Set<String> seenPids = new HashSet<>();
        for (String filename : indexedFilenames) {
            seenPids.add(filename.substring(DOC_PREFIX.length()));
        }
        int smokeLimit = Integer.parseInt(System.getenv().getOrDefault("AI_DU_BENCH_LIMIT", "0"));

        long startedAll = System.nanoTime();
        int totalPassages = 0;
        int skipped = 0;
        int chunkTotal = 0;

        // 批量累积：(pid, text) → chunks → embed → 批量落库
        List<String[]> pending = new ArrayList<>();
        int pendingChunks = 0;
        try (var reader = Files.newBufferedReader(CORPUS_JSONL, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode row = JSON.readTree(line);
                String pid = row.path("id").asText();
                String text = row.path("text").asText();
                totalPassages++;
                if (indexedFilenames.contains(DOC_PREFIX + pid)) {
                    skipped++;
                    continue;
                }
                if (text == null || text.isBlank()) continue;
                if (!seenPids.add(pid)) {
                    skipped++;
                    continue;
                }
                if (smokeLimit > 0 && skipped + totalPassages >= smokeLimit) {
                    System.out.println("DU_INDEX_SMOKE_LIMIT_REACHED limit=" + smokeLimit);
                    break;
                }
                pending.add(new String[]{pid, text});
                pendingChunks += estimateChunks(text);
                if (pending.size() >= 64 || pendingChunks >= 128) {
                    chunkTotal += flushBatch(pending);
                    pending.clear();
                    pendingChunks = 0;
                }
                if (totalPassages % 5000 == 0) {
                    long elapsedMin = (System.nanoTime() - startedAll) / 60_000_000_000L;
                    System.out.printf("DU_INDEX_PROGRESS passages=%d skipped=%d chunks=%d elapsedMin=%d%n",
                            totalPassages, skipped, chunkTotal, elapsedMin);
                }
            }
        }
        if (!pending.isEmpty()) {
            chunkTotal += flushBatch(pending);
            pending.clear();
        }

        long indexMin = (System.nanoTime() - startedAll) / 60_000_000_000L;
        Long docCount = jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE original_filename LIKE 'duretrieval/%'", Long.class);
        Long chunkCount = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk c JOIN project_document d ON d.id=c.document_id "
                        + "WHERE d.original_filename LIKE 'duretrieval/%'", Long.class);
        System.out.printf("DU_INDEX_DONE documents=%d chunks=%d skipped=%d elapsedMin=%d%n",
                docCount, chunkCount, skipped, indexMin);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("corpusMode", "OFFICIAL_CORPUS_FULL");
        summary.put("corpusPassages", totalPassages);
        summary.put("indexedDocuments", docCount);
        summary.put("indexedChunks", chunkCount);
        summary.put("skippedAlreadyIndexed", skipped);
        summary.put("elapsedMinutes", indexMin);
        summary.put("embeddingModel", EMBEDDING_MODEL);
        summary.put("fingerprint", FINGERPRINT);
        Files.createDirectories(OUTPUT);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                OUTPUT.resolve("index-summary.json").toFile(), summary);
    }

    private int estimateChunks(String text) {
        int len = text.length();
        if (len <= 1200) return 1;
        return Math.max(1, (len + 999) / 1000);
    }

    /** 批量嵌入与落库；返回 chunk 数。 */
    private long flushBatch(List<String[]> batch) throws Exception {
        // 每条 passage 独立切块（真实生产 Chunker），块内容聚合嵌入
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

        EmbeddingBatch vectors = embeddings.embed(project,
                passageChunks.stream().map(c -> c[2]).toList(), EmbeddingProgressListener.NONE);
        assertThat(vectors.dimension()).isEqualTo(1024);
        assertThat(vectors.vectors()).hasSize(passageChunks.size());

        // 文档（每 passage 一个文档）
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
            docRows.add(new Object[]{docId, project, e.getKey(), DOC_PREFIX + e.getKey(), "text/plain",
                    sizeByPid.get(e.getKey()), "duretrieval/" + e.getKey(), e.getValue(), EMBEDDING_MODEL, FINGERPRINT, user});
        }
        jdbc.batchUpdate("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,status,chunk_count,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,uploaded_by,indexed_at)
                VALUES (?,?,?,?,?,?,?,'READY',?,'OLLAMA',?,1024,?,?,now())
                """, docRows);

        List<Object[]> chunkRows = new ArrayList<>();
        for (int i = 0; i < passageChunks.size(); i++) {
            String[] c = passageChunks.get(i);
            chunkRows.add(new Object[]{UUID.randomUUID(), project, docIds.get(c[0]),
                    Integer.parseInt(c[1]), c[3], c[2], c[4], Integer.parseInt(c[5]), c[6],
                    EMBEDDING_MODEL, FINGERPRINT, vectors.vectors().get(i).toString(), generation});
        }
        jdbc.batchUpdate("""
                INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,
                  content_hash,token_estimate,metadata,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,embedding,generation_id)
                VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),'OLLAMA',?,1024,?,CAST(? AS vector),?)
                """, chunkRows);
        return passageChunks.size();
    }

    // ==================== 阶段二：六变体对照评测 ====================

    @Test
    @Order(2)
    void evaluateVariants() throws Exception {
        project = jdbc.queryForObject(
                "SELECT id FROM project WHERE name='DuRetrieval 公开基准评测' LIMIT 1", UUID.class);
        Long docCount = jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE status='READY' AND original_filename LIKE 'duretrieval/%'",
                Long.class);
        boolean smokeEval = Boolean.parseBoolean(System.getenv().getOrDefault("AI_DU_BENCH_SMOKE_EVAL", "false"));
        if (!smokeEval) {
            // 官方 corpus 100,001 行含 144 个重复 id 行，去重后独立文档 99,857（见 index-summary.json）
            assertThat(docCount).as("语料必须已索引").isGreaterThanOrEqualTo(99_000L);
        }

        JsonNode manifest = JSON.readTree(SAMPLE_MANIFEST.toFile());
        List<JsonNode> queries = new ArrayList<>();
        manifest.path("duretrieval").path("samples").forEach(queries::add);
        assertThat(queries).hasSize(300);
        int queryLimit = Integer.parseInt(System.getenv().getOrDefault("AI_DU_BENCH_QUERY_LIMIT", "0"));
        if (queryLimit > 0) {
            queries = queries.subList(0, Math.min(queryLimit, queries.size()));
        }

        // 六变体配置
        Map<String, RetrievalPipeline.PipelineConfig> pipelineVariants = new LinkedHashMap<>();
        pipelineVariants.put("A3_DENSE_LEXICAL_RRF", new RetrievalPipeline.PipelineConfig(
                CANDIDATE_TOP_K, CANDIDATE_TOP_K, CANDIDATE_TOP_K, POOL, false, 1, false, RerankerType.PASS_THROUGH, List.of(), null));
        pipelineVariants.put("A4_HYBRID_SENTENCE_WINDOW", new RetrievalPipeline.PipelineConfig(
                CANDIDATE_TOP_K, CANDIDATE_TOP_K, CANDIDATE_TOP_K, POOL, true, 1, false, RerankerType.PASS_THROUGH, List.of(), null));
        pipelineVariants.put("A5_HYBRID_PARENT_CHILD", new RetrievalPipeline.PipelineConfig(
                CANDIDATE_TOP_K, CANDIDATE_TOP_K, CANDIDATE_TOP_K, POOL, false, 1, true, RerankerType.PASS_THROUGH, List.of(), null));
        pipelineVariants.put("A6_RAG_V2_FULL_DEFAULT", RetrievalPipeline.PipelineConfig.defaultConfig());
        List<String> variantNames = new ArrayList<>(List.of("A1_DENSE_ONLY", "A2_LEXICAL_ONLY"));
        variantNames.addAll(pipelineVariants.keySet());

        Map<String, List<Map<String, Object>>> perQueryRecords = new LinkedHashMap<>();
        Map<String, List<Long>> latencies = new LinkedHashMap<>();
        for (String v : variantNames) {
            perQueryRecords.put(v, new ArrayList<>());
            latencies.put(v, new ArrayList<>());
        }

        int done = 0;
        for (JsonNode q : queries) {
            String qid = q.path("qid").asText();
            String query = q.path("query").asText();
            Set<String> relevant = new HashSet<>();
            q.path("relevantPids").forEach(p -> relevant.add(p.asText()));

            for (String variant : variantNames) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("qid", qid);
                record.put("variant", variant);
                try {
                    long started = System.nanoTime();
                    List<String> rankedDocs = retrieveDocs(variant, query);
                    long latencyMs = (System.nanoTime() - started) / 1_000_000L;
                    latencies.get(variant).add(latencyMs);

                    List<String> top10 = rankedDocs.stream().limit(10).toList();
                    int hitsAt5 = (int) top10.stream().limit(5).filter(relevant::contains).count();
                    int hitsAt10 = (int) top10.stream().filter(relevant::contains).count();
                    int firstHitRank = 0;
                    for (int i = 0; i < top10.size(); i++) {
                        if (relevant.contains(top10.get(i))) { firstHitRank = i + 1; break; }
                    }
                    double dcg = 0;
                    for (int i = 0; i < top10.size(); i++) {
                        if (relevant.contains(top10.get(i))) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
                    }
                    double idcg = 0;
                    int ideal = Math.min(relevant.size(), 10);
                    for (int i = 0; i < ideal; i++) idcg += 1.0 / (Math.log(i + 2) / Math.log(2));

                    record.put("rankedDocIds", top10);
                    record.put("hitRanks", top10.stream()
                            .map(d -> relevant.contains(d) ? top10.indexOf(d) + 1 : 0)
                            .filter(r -> r > 0).toList());
                    record.put("hitsAt5", hitsAt5);
                    record.put("hitsAt10", hitsAt10);
                    record.put("relevantCount", relevant.size());
                    record.put("reciprocalRank", firstHitRank > 0 ? 1.0 / firstHitRank : 0.0);
                    record.put("ndcg", idcg > 0 ? dcg / idcg : 0.0);
                    record.put("successAt10", hitsAt10 > 0);
                    record.put("latencyMs", latencyMs);
                    record.put("candidateDocCount", rankedDocs.size());
                } catch (Exception e) {
                    record.put("failed", true);
                    record.put("failureReason", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                perQueryRecords.get(variant).add(record);
            }
            done++;
            if (done % 25 == 0) System.out.println("DU_EVAL_PROGRESS " + done + "/" + queries.size());
        }

        // 汇总指标
        Map<String, Map<String, Object>> variantSummary = new LinkedHashMap<>();
        for (String variant : variantNames) {
            List<Map<String, Object>> rows = perQueryRecords.get(variant);
            List<Map<String, Object>> ok = rows.stream().filter(r -> !r.containsKey("failed")).toList();
            int n = ok.size();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("evaluatedQueries", n);
            s.put("failedQueries", rows.size() - n);
            if (n > 0) {
                s.put("recallAt5", ok.stream().mapToDouble(r -> (int) r.get("hitsAt5") / (double) (int) r.get("relevantCount")).average().orElse(0));
                s.put("recallAt10", ok.stream().mapToDouble(r -> (int) r.get("hitsAt10") / (double) (int) r.get("relevantCount")).average().orElse(0));
                s.put("mrrAt10", ok.stream().mapToDouble(r -> (double) r.get("reciprocalRank")).average().orElse(0));
                s.put("ndcgAt10", ok.stream().mapToDouble(r -> (double) r.get("ndcg")).average().orElse(0));
                s.put("precisionAt5", ok.stream().mapToDouble(r -> (int) r.get("hitsAt5") / 5.0).average().orElse(0));
                s.put("successRateAt10", ok.stream().mapToDouble(r -> (Boolean) r.get("successAt10") ? 1 : 0).average().orElse(0));
                List<Long> lat = latencies.get(variant);
                Collections.sort(lat);
                s.put("latencyP50Ms", percentile(lat, 50));
                s.put("latencyP95Ms", percentile(lat, 95));
                s.put("avgCandidateDocs", ok.stream().mapToInt(r -> (int) r.get("candidateDocCount")).average().orElse(0));
            }
            variantSummary.put(variant, s);
        }

        // 逐题差异（A3 RRF 相对 A1 Dense / A6 相对 A3 / A2 独立贡献）
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("hybridBetterThanDense", countDiff(perQueryRecords, "A3_DENSE_LEXICAL_RRF", "A1_DENSE_ONLY", true));
        diff.put("hybridWorseThanDense", countDiff(perQueryRecords, "A3_DENSE_LEXICAL_RRF", "A1_DENSE_ONLY", false));
        diff.put("lexicalUniqueHits", lexicalUniqueHits(perQueryRecords));
        diff.put("fullDefaultWorseThanRrf", countDiff(perQueryRecords, "A6_RAG_V2_FULL_DEFAULT", "A3_DENSE_LEXICAL_RRF", false));
        diff.put("fullDefaultBetterThanRrf", countDiff(perQueryRecords, "A6_RAG_V2_FULL_DEFAULT", "A3_DENSE_LEXICAL_RRF", true));
        diff.put("windowRankGainOverRrf", countDiff(perQueryRecords, "A4_HYBRID_SENTENCE_WINDOW", "A3_DENSE_LEXICAL_RRF", true));
        diff.put("parentRankGainOverRrf", countDiff(perQueryRecords, "A5_HYBRID_PARENT_CHILD", "A3_DENSE_LEXICAL_RRF", true));

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("benchmark", "C-MTEB/DuRetrieval (dev qrels, OFFICIAL_CORPUS_FULL)");
        results.put("corpusMode", "OFFICIAL_CORPUS_FULL");
        results.put("sampleSize", queries.size());
        results.put("candidatePool", POOL);
        results.put("embeddingModel", EMBEDDING_MODEL);
        results.put("fingerprint", FINGERPRINT);
        results.put("evalTopK", EVAL_TOP_K);
        results.put("variantSummary", variantSummary);
        results.put("perQueryDiff", diff);
        results.put("records", perQueryRecords);

        Files.createDirectories(OUTPUT);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                RESULT_FILE.toFile(), results);

        // CSV 汇总
        StringBuilder csv = new StringBuilder("variant,evaluatedQueries,recallAt5,recallAt10,mrrAt10,ndcgAt10,precisionAt5,successRateAt10,latencyP50Ms,latencyP95Ms,avgCandidateDocs\n");
        for (String variant : variantNames) {
            Map<String, Object> s = variantSummary.get(variant);
            csv.append(variant).append(',')
                    .append(s.get("evaluatedQueries")).append(',')
                    .append(fmt(s.get("recallAt5"))).append(',')
                    .append(fmt(s.get("recallAt10"))).append(',')
                    .append(fmt(s.get("mrrAt10"))).append(',')
                    .append(fmt(s.get("ndcgAt10"))).append(',')
                    .append(fmt(s.get("precisionAt5"))).append(',')
                    .append(fmt(s.get("successRateAt10"))).append(',')
                    .append(s.get("latencyP50Ms")).append(',')
                    .append(s.get("latencyP95Ms")).append(',')
                    .append(fmt(s.get("avgCandidateDocs"))).append('\n');
        }
        Files.writeString(SUMMARY_FILE, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("DU_BENCHMARK_RESULTS=" + OUTPUT.toAbsolutePath());
        System.out.println(csv);
    }

    /** 按变体执行检索，返回文档级去重排名（pid 列表，首次出现序）。 */
    private List<String> retrieveDocs(String variant, String query) {
        List<RetrievalCandidate> candidates;
        switch (variant) {
            case "A1_DENSE_ONLY" -> candidates = denseRetriever.retrieve(project, query, null, POOL);
            case "A2_LEXICAL_ONLY" -> candidates = lexicalRetriever.retrieve(project, query, null, POOL);
            case "A3_DENSE_LEXICAL_RRF" -> candidates = pipeline.execute(
                    project, query, null, List.of(),
                    new RetrievalPipeline.PipelineConfig(CANDIDATE_TOP_K, CANDIDATE_TOP_K, CANDIDATE_TOP_K,
                            POOL, false, 1, false, RerankerType.PASS_THROUGH, List.of(), null)).candidates();
            case "A4_HYBRID_SENTENCE_WINDOW" -> candidates = pipeline.execute(
                    project, query, null, List.of(),
                    new RetrievalPipeline.PipelineConfig(CANDIDATE_TOP_K, CANDIDATE_TOP_K, CANDIDATE_TOP_K,
                            POOL, true, 1, false, RerankerType.PASS_THROUGH, List.of(), null)).candidates();
            case "A5_HYBRID_PARENT_CHILD" -> candidates = pipeline.execute(
                    project, query, null, List.of(),
                    new RetrievalPipeline.PipelineConfig(CANDIDATE_TOP_K, CANDIDATE_TOP_K, CANDIDATE_TOP_K,
                            POOL, false, 1, true, RerankerType.PASS_THROUGH, List.of(), null)).candidates();
            case "A6_RAG_V2_FULL_DEFAULT" -> candidates = pipeline.execute(
                    project, query, null, List.of(), RetrievalPipeline.PipelineConfig.defaultConfig()).candidates();
            default -> throw new IllegalArgumentException("未知变体 " + variant);
        }
        // Chunk → 公开文档身份还原：同一 passage 的多个 chunk 只计一次（保留最优排名）
        return candidates.stream()
                .map(RetrievalCandidate::originalFilename)
                .filter(Objects::nonNull)
                .map(f -> f.startsWith(DOC_PREFIX) ? f.substring(DOC_PREFIX.length()) : f)
                .distinct()
                .toList();
    }

    private List<String> countDiff(Map<String, List<Map<String, Object>>> records, String a, String b, boolean aBetter) {
        Map<String, Map<String, Object>> byQidA = records.get(a).stream()
                .filter(r -> !r.containsKey("failed"))
                .collect(Collectors.toMap(r -> (String) r.get("qid"), r -> r));
        List<String> diffs = new ArrayList<>();
        for (Map<String, Object> rowB : records.get(b)) {
            if (rowB.containsKey("failed")) continue;
            Map<String, Object> rowA = byQidA.get(rowB.get("qid"));
            if (rowA == null) continue;
            double metricA = (double) rowA.get("ndcg");
            double metricB = (double) rowB.get("ndcg");
            boolean better = metricA > metricB + 1e-9;
            boolean worse = metricA < metricB - 1e-9;
            if (aBetter && better) diffs.add((String) rowA.get("qid"));
            if (!aBetter && worse) diffs.add((String) rowA.get("qid"));
        }
        return diffs;
    }

    /** Lexical 独立贡献：仅 A2 命中（top10）而 A1 未命中的 qid。 */
    private List<String> lexicalUniqueHits(Map<String, List<Map<String, Object>>> records) {
        Map<String, Map<String, Object>> byQidA = records.get("A1_DENSE_ONLY").stream()
                .filter(r -> !r.containsKey("failed"))
                .collect(Collectors.toMap(r -> (String) r.get("qid"), r -> r));
        List<String> unique = new ArrayList<>();
        for (Map<String, Object> row : records.get("A2_LEXICAL_ONLY")) {
            if (row.containsKey("failed")) continue;
            Map<String, Object> denseRow = byQidA.get(row.get("qid"));
            if (denseRow == null) continue;
            boolean lexicalHit = (int) row.get("hitsAt10") > 0;
            boolean denseHit = (int) denseRow.get("hitsAt10") > 0;
            if (lexicalHit && !denseHit) unique.add((String) row.get("qid"));
        }
        return unique;
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
