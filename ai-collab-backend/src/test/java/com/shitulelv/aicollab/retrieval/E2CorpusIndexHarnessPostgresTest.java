package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.job.AiIndexJobService;
import com.shitulelv.aicollab.agent.job.AiJobRecord;
import com.shitulelv.aicollab.agent.job.AiJobService;
import com.shitulelv.aicollab.agent.job.AiJobStatus;
import com.shitulelv.aicollab.agent.job.AiJobTypes;
import com.shitulelv.aicollab.agent.job.AiTaskStreamPublisher;
import com.shitulelv.aicollab.document.application.service.DocumentContentService;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.parser.DocumentParser;
import com.shitulelv.aicollab.document.infrastructure.parser.ParsedDocument;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 RAG 检索质量评测的<b>真实链路索引 harness</b>（P2 Python worker 契约：Redis Stream + ai_job）。
 *
 * <p>与 {@link P9RagGeneralizationPostgresTest} 的时代背景不同：P9 在 Java 进程内直写旧
 * {@code document_chunk} 表；本 harness 走<b>当前生产链路</b>——文档经真实
 * {@code TikaDocumentParser} 解析与 {@code DocumentChunker} 切块后经
 * {@code DocumentContentService.saveParsed} 入库（{@code project_document / document_body /
 * document_body_chunk}），再经 {@code AiIndexJobService.enqueueIndexDocument} 派发真实
 * {@code INDEX_DOCUMENT} 任务（事务内写 {@code ai_job(QUEUED)} + AFTER_COMMIT XADD 到
 * Redis Stream {@code aicollab:ai:tasks}），由外部真实 Python worker 消费建
 * {@code ai_index}。harness 自己<b>绝不写</b> {@code ai_index}。</p>
 *
 * <p><b>documentId 钉住契约</b>：E2 评测脚本
 * {@code ai-collab-ai-worker/src/ai_collab_worker/eval/e2_rag.py} 要求 {@code ai_index} 里的
 * documentId 等于 Python {@code uuid.uuid5(uuid.NAMESPACE_URL, "ai-collab:e2:" + filename)}
 * （RFC 4122 v5，SHA-1）。Java 内置只有 v3，这里自行实现 v5 算法并用已知向量自检，
 * 保证与评测 CLI 默认 {@code --project-id 22222222-2222-4222-8222-222222222222}、
 * {@code --user-id 11111111-1111-4111-8111-111111111111} 一致。</p>
 *
 * <p><b>运行方式</b>（整类由 {@code E2_INDEX_ONLY=true} 显式门控，平时 verify 不误跑）：</p>
 * <pre>
 * # 本地 Testcontainers 模式（默认 worker-mock 消费者把任务置 SUCCEEDED，验证派发+对账闭环）
 * cd ai-collab-backend
 * E2_INDEX_ONLY=true bash ./mvnw -B -ntp test -Dtest=E2CorpusIndexHarnessPostgresTest
 *
 * # 外部真实栈模式（连已起的后端库/Redis，真实 Python worker 消费建 ai_index）
 * E2_INDEX_ONLY=true \
 * E2_INDEX_DB_URL=jdbc:postgresql://localhost:5432/ai_collab \
 * E2_INDEX_DB_USERNAME=&lt;用户&gt; E2_INDEX_DB_PASSWORD=&lt;密码&gt; \
 * E2_INDEX_REDIS_HOST=localhost E2_INDEX_REDIS_PORT=6379 \
 * E2_INDEX_WORKER_MOCK=false \
 * bash ./mvnw -B -ntp test -Dtest=E2CorpusIndexHarnessPostgresTest
 * </pre>
 *
 * <p>环境变量：{@code E2_INDEX_DB_URL/USERNAME/PASSWORD}（外部 PostgreSQL，缺省回落
 * Testcontainers pgvector 容器）；{@code E2_INDEX_REDIS_HOST/PORT}（外部 Redis，缺省回落容器）；
 * {@code E2_INDEX_WORKER_MOCK}（默认 {@code true}：起进程内假消费者按真实 result 契约把任务置
 * SUCCEEDED；外部真实 worker 时必须置 {@code false}）；{@code E2_INDEX_CLEAR=true} 删除本项目
 * 旧文档后全量重建；{@code E2_INDEX_WAIT_TIMEOUT_SECONDS}（默认 600，对账等待上限）。</p>
 *
 * <p><b>幂等</b>：评测用户/项目按固定 uuid {@code ON CONFLICT DO NOTHING}；文档已存在（有正文）
 * 则跳过入库但重派 {@code INDEX_DOCUMENT} 任务；{@code E2_INDEX_CLEAR=true} 时先清旧行再重建。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
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
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "E2_INDEX_ONLY", matches = "true")
class E2CorpusIndexHarnessPostgresTest {
    private static final Logger log = LoggerFactory.getLogger(E2CorpusIndexHarnessPostgresTest.class);

    /** 评测 CLI（ai_collab_worker.eval.cli）的默认 project/user，必须与评测命令一致。 */
    static final UUID E2_PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID E2_USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** RFC 4122 §C.2 的 URL namespace（Python uuid.NAMESPACE_URL 同值）。 */
    static final UUID NAMESPACE_URL = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    /** v5 算法自检向量：Python uuid5(NAMESPACE_URL, "python.org") 的公认结果。 */
    private static final String UUID5_SELF_CHECK = "7af94e2b-4dd9-50f0-9c9a-8a48519bdef0";
    /** e2_rag.py 的 name 前缀。 */
    private static final String DOCUMENT_NAME_PREFIX = "ai-collab:e2:";

    /** worker-mock 模式写进 result payload 的 generationId（仅 mock 链路使用，非真实 ai_index generation）。 */
    private static final UUID MOCK_GENERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String MOCK_EMBEDDING_MODEL = "mock-embedder";
    private static final int MOCK_EMBEDDING_DIMENSION = 1024;

    /** 冻结语料：P9-RAG-GENERALIZATION-01 的 23 篇（manifest 与 docs/acceptance-evidence 一致）。 */
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path CORPUS_DIR = ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");
    private static final String CORPUS_MANIFEST = "corpus-manifest.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /**
     * 数据源/Redis 支持环境变量覆盖（外部真实栈模式），有值优先、缺省回落 Testcontainers 容器。
     * 密码等凭据只经环境变量注入，绝不写死在代码里。
     */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        if (isBlank(env("E2_INDEX_DB_URL")) || isBlank(env("E2_INDEX_DB_USERNAME"))) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        } else {
            log.info("E2 harness 使用外部 PostgreSQL: {}", env("E2_INDEX_DB_URL"));
            registry.add("spring.datasource.url", () -> env("E2_INDEX_DB_URL"));
            registry.add("spring.datasource.username", () -> env("E2_INDEX_DB_USERNAME"));
            registry.add("spring.datasource.password", () -> env("E2_INDEX_DB_PASSWORD"));
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }
        if (isBlank(env("E2_INDEX_REDIS_HOST"))) {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        } else {
            log.info("E2 harness 使用外部 Redis: {}:{}",
                    env("E2_INDEX_REDIS_HOST"), env("E2_INDEX_REDIS_PORT"));
            registry.add("spring.data.redis.host", () -> env("E2_INDEX_REDIS_HOST"));
            registry.add("spring.data.redis.port", () -> Integer.parseInt(env("E2_INDEX_REDIS_PORT")));
        }
        // 外部 Redis 通常开了 requirepass；测试容器模式本地无密码。
        if (!isBlank(env("E2_INDEX_REDIS_PASSWORD"))) {
            registry.add("spring.data.redis.password", () -> env("E2_INDEX_REDIS_PASSWORD"));
        }
    }

    @MockitoBean
    TaskPlanRecoveryJob taskPlanRecoveryJob;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired DocumentParser parser;
    @Autowired DocumentChunker chunker;
    @Autowired DocumentContentService content;
    @Autowired DocumentRepository documents;
    @Autowired AiIndexJobService indexJobs;
    @Autowired AiJobService aiJobs;
    @Autowired StringRedisTemplate redis;

    /** worker-mock 首次失败记录（AiJobException 的 message 是固定文案，不含令牌/正文，可安全记录）。 */
    private final Map<UUID, String> mockFirstFailures = new LinkedHashMap<>();

    /** 一篇冻结语料的解析结果（documentId 已按 uuid5 钉住）。 */
    private record CorpusDoc(String filename, String family, String datasetSplit, String sha256,
                             UUID documentId, byte[] raw) {
    }

    /** 一篇语料的对账行。 */
    private record ReconcileRow(CorpusDoc doc, int bodyRows, int parentRows, long embeddedExpected,
                                String documentStatus, List<String> jobStatuses, boolean dispatched,
                                String note) {
    }

    @Test
    void indexFrozenCorpusThroughRealPipeline() throws Exception {
        // v5 算法自检：与 Python uuid5 的公认结果比对，防止实现漂移导致评测对不上 documentId。
        assertThat(uuid5(NAMESPACE_URL, "python.org").toString())
                .as("uuid5 实现必须与 Python uuid.uuid5 一致").isEqualTo(UUID5_SELF_CHECK);

        seedEvaluationProjectAndUser();
        if (clearRequested()) {
            clearPreviousRun();
        }

        List<CorpusDoc> corpus = loadAndVerifyCorpus();
        assertThat(corpus).as("冻结语料必须是 23 篇").hasSize(23);

        // ---- 入库 + 派发（每篇独立事务，派发语义与生产 DocumentIndexSubmission.submit 完全一致）----
        Map<String, List<UUID>> jobIdsByFile = new LinkedHashMap<>();
        for (CorpusDoc doc : corpus) {
            jobIdsByFile.put(doc.filename(), ingestOrRedispatch(doc));
        }
        List<UUID> allJobIds = jobIdsByFile.values().stream().flatMap(List::stream).toList();
        assertThat(allJobIds).as("每篇语料都必须派发出 INDEX_DOCUMENT 任务").hasSize(corpus.size());

        // ---- 派发验证：任务必须真的进了 Redis Stream（XADD 失败只记日志不回滚，这里显式把失败暴露出来）----
        assertTasksPublishedToStream(allJobIds);

        // ---- 等待对账：轮询 ai_job 至终态（mock 模式由进程内假消费者按真实 result 契约推进）----
        boolean workerMock = workerMockEnabled();
        long timeoutSeconds = waitTimeoutSeconds();
        Map<UUID, AiJobStatus> finalStatuses = waitForTerminal(allJobIds, workerMock, timeoutSeconds);

        List<ReconcileRow> rows = reconcile(corpus, jobIdsByFile, finalStatuses);
        printReconciliation(rows);

        // ---- 结果判定 ----
        long succeeded = finalStatuses.values().stream().filter(s -> s == AiJobStatus.SUCCEEDED).count();
        long failedOrWorse = finalStatuses.values().stream()
                .filter(s -> s == AiJobStatus.FAILED || s == AiJobStatus.EXPIRED || s == AiJobStatus.CANCELED)
                .count();
        boolean allTerminal = finalStatuses.values().stream().allMatch(AiJobStatus::terminal);

        if (failedOrWorse > 0) {
            throw new IllegalStateException("存在 " + failedOrWorse + " 个进入失败终态的 INDEX_DOCUMENT 任务，"
                    + "索引链路契约被破坏（见上方对账表）");
        }
        if (!allTerminal) {
            if (workerMock) {
                throw new IllegalStateException(
                        "worker-mock 模式下等待超时仍有任务未到终态：派发或 result 回调链路异常。首次失败原因："
                                + mockFirstFailures);
            }
            log.warn("E2_INDEX_WAIT_TIMEOUT_SECONDS={}s 到期仍有任务未到终态（QUEUED/LEASED/RUNNING）。"
                    + "外部 worker 模式下这是预期内的警告：请确认 Python worker 已启动并消费 Redis Stream "
                    + "aicollab:ai:tasks（消费组 ai-worker），任务会在 worker 消费后推进到 SUCCEEDED。", timeoutSeconds);
        }
        if (succeeded == allJobIds.size()) {
            // 全部成功时做严格断言：文档必须 READY 且 chunk_count 与正文行数一致。
            for (ReconcileRow row : rows) {
                assertThat(row.documentStatus())
                        .as("%s 最终必须 READY", row.doc().filename()).isEqualTo("READY");
                assertThat(row.jobStatuses())
                        .as("%s 的全部任务必须 SUCCEEDED", row.doc().filename())
                        .containsOnly(AiJobStatus.SUCCEEDED.name());
            }
            log.info("E2 harness 对账全部通过：{} 篇语料全部 READY，{} 个任务全部 SUCCEEDED",
                    corpus.size(), allJobIds.size());
        }
    }

    // ---- 评测用户/项目（固定 uuid，幂等）----

    private void seedEvaluationProjectAndUser() {
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, display_name)
                VALUES (?, ?, 'test-only-hash', 'E2 索引评测') ON CONFLICT (id) DO NOTHING
                """, E2_USER_ID, "e2-index-" + E2_USER_ID.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO project(id, name, owner_id, created_by)
                VALUES (?, 'E2 RAG 检索质量评测', ?, ?) ON CONFLICT (id) DO NOTHING
                """, E2_PROJECT_ID, E2_USER_ID, E2_USER_ID);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER') "
                + "ON CONFLICT DO NOTHING", E2_PROJECT_ID, E2_USER_ID);
        log.info("E2 评测项目就绪 projectId={} userId={}", E2_PROJECT_ID, E2_USER_ID);
    }

    /** CLEAR 重建：清掉本项目的旧文档（正文随级联删除）与历史任务（ai_job 结果/尝试随级联删除）。 */
    private void clearPreviousRun() {
        int jobs = jdbc.update("DELETE FROM ai_job WHERE project_id=?", E2_PROJECT_ID);
        int docs = jdbc.update("DELETE FROM project_document WHERE project_id=?", E2_PROJECT_ID);
        log.info("E2_INDEX_CLEAR=true：已清理旧任务 {} 个、旧文档 {} 篇（真实 worker 侧 ai_index 行由"
                + "重派任务的删旧插新覆盖，不在 Java 侧清理）", jobs, docs);
    }

    // ---- 冻结语料装载（manifest 驱动，逐篇 SHA-256 校验 + uuid5 钉 id）----

    private List<CorpusDoc> loadAndVerifyCorpus() throws Exception {
        JsonNode manifest = JSON.readTree(CORPUS_DIR.resolve(CORPUS_MANIFEST).toFile());
        List<CorpusDoc> corpus = new ArrayList<>();
        for (JsonNode entry : manifest.path("documents")) {
            String filename = entry.path("filename").asText();
            Path file = CORPUS_DIR.resolve(filename);
            assertThat(Files.exists(file)).as("冻结语料必须存在: %s", filename).isTrue();
            byte[] raw = Files.readAllBytes(file);
            String actual = sha256Hex(raw);
            assertThat(actual)
                    .as("冻结语料 %s 必须与 manifest 哈希逐字节一致", filename)
                    .isEqualTo(entry.path("sha256").asText());
            UUID documentId = uuid5(NAMESPACE_URL, DOCUMENT_NAME_PREFIX + filename);
            corpus.add(new CorpusDoc(filename, entry.path("family").asText(),
                    entry.path("set").asText(), actual, documentId, raw));
        }
        return corpus;
    }

    // ---- 入库 + 派发（真实链路；幂等：已有正文则只重派任务）----

    private List<UUID> ingestOrRedispatch(CorpusDoc doc) throws Exception {
        String status = jdbc.query(
                "SELECT status FROM project_document WHERE id=? AND project_id=?",
                (rs, row) -> rs.getString(1), doc.documentId(), E2_PROJECT_ID)
                .stream().findFirst().orElse(null);
        boolean hasBody = status != null && !jdbc.queryForList(
                "SELECT document_id FROM document_body WHERE document_id=?", UUID.class, doc.documentId())
                .isEmpty();
        if (status != null && hasBody) {
            log.info("[跳过入库] {} 已存在且正文完整（status={}），仅重派 INDEX_DOCUMENT 任务",
                    doc.filename(), status);
            return List.of(dispatchIndexJob(doc).id());
        }
        if (status != null) {
            // 上次运行中断留下的骨架行（PARSING/INDEXING 无正文）：claim/快照契约都不满足，必须 CLEAR 重建。
            log.warn("[入库受阻] {} 存在骨架行 status={} 但无 document_body 正文（上次运行中断）。"
                    + "跳过派发；请用 E2_INDEX_CLEAR=true 重建", doc.filename(), status);
            return List.of();
        }
        return List.of(ingestAndDispatch(doc).id());
    }

    /**
     * 复刻生产 {@code DocumentProcessingService.process + DocumentIndexSubmission.submit} 的真实链路：
     * 骨架行（UPLOADED，钉住 uuid5 id）→ claim(PARSING+processing_token) → Tika 解析 → 切块 →
     * saveParsed(document_body/document_body_chunk，行 id 用切块器 id) → markIndexing(INDEXING) →
     * enqueueIndexDocument（ai_job QUEUED + AFTER_COMMIT XADD）。仅省略 MinIO 读取：语料字节来自本地冻结文件。
     */
    private AiJobRecord ingestAndDispatch(CorpusDoc doc) {
        AiJobRecord job = tx.execute(ignored -> {
            DocumentEntity entity = new DocumentEntity();
            entity.setId(doc.documentId());
            entity.setProjectId(E2_PROJECT_ID);
            entity.setDisplayName(doc.filename());
            entity.setOriginalFilename(doc.filename());
            entity.setMimeType("text/markdown");
            entity.setSizeBytes((long) doc.raw().length);
            entity.setObjectKey("e2/" + doc.documentId());
            entity.setStatus(DocumentStatus.UPLOADED);
            entity.setChunkCount(0);
            entity.setUploadedBy(E2_USER_ID);
            documents.create(entity);

            UUID processingToken = UUID.randomUUID();
            assertThat(documents.claim(E2_PROJECT_ID, doc.documentId(), processingToken))
                    .as("%s claim 必须 succeed", doc.filename()).isTrue();

            ParsedDocument parsed = parser.parse(doc.raw(), doc.filename(), "text/markdown");
            // 生产默认开启句子级索引（rag.indexing.enable-sentence-indexing:true）。
            // E2_INDEX_SENTENCE_INDEXING=false 可切换到无句子分块轮廓（P9 基线 175 块口径），
            // 用于归因实验：区分「分块轮廓交互」与「P2 检索回归」。
            boolean sentenceIndexing = !"false".equals(System.getenv("E2_INDEX_SENTENCE_INDEXING"));
            List<DocumentChunk> chunks = chunker.split(parsed.text(), parsed.pageBoundaries(), sentenceIndexing);
            log.info("E2 harness {} 分块 {} 块（句子级索引={}）", doc.filename(), chunks.size(), sentenceIndexing);
            content.saveParsed(E2_PROJECT_ID, doc.documentId(), processingToken, doc.raw(), chunks);
            assertThat(documents.markIndexing(E2_PROJECT_ID, doc.documentId(), processingToken,
                    parsed.parserType())).as("%s markIndexing 必须 succeed", doc.filename()).isTrue();

            AiJobRecord created = indexJobs.enqueueIndexDocument(E2_PROJECT_ID, doc.documentId());
            long parents = chunks.stream().filter(c -> DocumentChunk.TYPE_PARENT.equals(c.chunkType())).count();
            log.info("[入库+派发] {} documentId={} chunks={}（PARENT={}，worker 将向量化 {} 行）jobId={} 任务={}",
                    doc.filename(), doc.documentId(), chunks.size(), parents, chunks.size() - parents,
                    created.id(), created.jobType());
            return created;
        });
        assertThat(job).as("%s 必须派发出任务", doc.filename()).isNotNull();
        return job;
    }

    /** 已有正文的文档重派任务（worker 端删旧插新幂等覆盖）。 */
    private AiJobRecord dispatchIndexJob(CorpusDoc doc) {
        AiJobRecord job = indexJobs.enqueueIndexDocument(E2_PROJECT_ID, doc.documentId());
        log.info("[重派] {} jobId={} 任务={}", doc.filename(), job.id(), job.jobType());
        return job;
    }

    // ---- 派发验证与等待对账 ----

    /** 每个任务必须在 Stream {@code aicollab:ai:tasks} 里有一条消息（taskType/taskId/epoch/token）。 */
    private void assertTasksPublishedToStream(List<UUID> jobIds) {
        Set<String> taskIdsInStream = new LinkedHashSet<>();
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(AiTaskStreamPublisher.STREAM_KEY, Range.unbounded());
        if (records != null) {
            for (MapRecord<String, Object, Object> record : records) {
                Map<Object, Object> fields = record.getValue();
                if (AiJobTypes.INDEX_DOCUMENT.equals(String.valueOf(fields.get("taskType")))) {
                    taskIdsInStream.add(String.valueOf(fields.get("taskId")));
                }
            }
        }
        List<String> missing = jobIds.stream().map(UUID::toString)
                .filter(id -> !taskIdsInStream.contains(id)).toList();
        assertThat(missing)
                .as("全部任务必须已 XADD 到 Redis Stream %s（缺失说明 XADD 失败或 Redis 不可用）",
                        AiTaskStreamPublisher.STREAM_KEY)
                .isEmpty();
        log.info("派发验证：{} 个 INDEX_DOCUMENT 任务均在 Redis Stream {} 中（消费组 {}）",
                jobIds.size(), AiTaskStreamPublisher.STREAM_KEY, AiTaskStreamPublisher.CONSUMER_GROUP);
    }

    /**
     * 轮询 {@code ai_job} 至全部终态。worker-mock 模式下先由进程内假消费者按真实契约推进
     * （claim → 读正文快照 → submitIndexResult），等价于真实 Python worker 的 Java 侧交互。
     */
    private Map<UUID, AiJobStatus> waitForTerminal(List<UUID> jobIds, boolean workerMock,
            long timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        Map<UUID, AiJobStatus> statuses = new LinkedHashMap<>();
        while (true) {
            if (workerMock) {
                runMockWorkerPass(jobIds);
            }
            statuses.clear();
            for (UUID jobId : jobIds) {
                statuses.put(jobId, queryJobStatus(jobId));
            }
            if (statuses.values().stream().allMatch(AiJobStatus::terminal)) {
                return statuses;
            }
            if (System.currentTimeMillis() >= deadline) {
                return statuses;
            }
            Thread.sleep(2000L);
        }
    }

    private AiJobStatus queryJobStatus(UUID jobId) {
        String status = jdbc.queryForObject("SELECT status FROM ai_job WHERE id=?", String.class, jobId);
        return AiJobStatus.from(status);
    }

    /**
     * 进程内假 worker：从 Redis Stream 读 INDEX_DOCUMENT 消息，按真实 Python worker 的 Java 侧交互
     * （claim 拿租约 → 读正文快照 → 按 result 契约提交 embeddedRows）推进任务。claim 冲突视为
     * 已被外部 worker 认领，跳过交给对方。绝不记录消息里的 token 字段。
     */
    private void runMockWorkerPass(List<UUID> jobIds) {
        Set<String> watched = new LinkedHashSet<>();
        jobIds.forEach(id -> watched.add(id.toString()));
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(AiTaskStreamPublisher.STREAM_KEY, Range.unbounded());
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> fields = record.getValue();
            String taskType = String.valueOf(fields.get("taskType"));
            String taskId = String.valueOf(fields.get("taskId"));
            if (!AiJobTypes.INDEX_DOCUMENT.equals(taskType) || !watched.contains(taskId)) {
                continue;
            }
            UUID jobId = UUID.fromString(taskId);
            AiJobStatus status = queryJobStatus(jobId);
            if (status.terminal()) {
                continue;
            }
            long epoch = Long.parseLong(String.valueOf(fields.get("epoch")));
            try {
                mockProcessIndexDocument(jobId, epoch);
            } catch (Exception failure) {
                // claim 冲突/快照校验失败都记录下来；AiJobException 文案是固定短语，不含敏感信息。
                String detail = failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : (": " + failure.getMessage()));
                log.info("[worker-mock] 任务 {} 本轮未推进：{}", taskId, detail);
                mockFirstFailures.putIfAbsent(jobId, detail);
            }
        }
    }

    /** 复刻 IndexDocumentHandler 的 Java 侧契约：claim → 快照 → result{documentId, bodySnapshotId, generations}。 */
    private void mockProcessIndexDocument(UUID jobId, long epoch) {
        AiJobService.Lease lease = aiJobs.claim(jobId, AiJobTypes.INDEX_DOCUMENT, epoch);
        UUID documentId = lease.job().documentId();
        UUID bodySnapshotId = jdbc.queryForObject(
                "SELECT snapshot_id FROM document_body WHERE document_id=?", UUID.class, documentId);
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE metadata->>'chunkType' = 'PARENT') AS parents
                FROM document_body_chunk WHERE document_id=?
                """, documentId);
        int total = ((Number) counts.get("total")).intValue();
        int embedded = total - ((Number) counts.get("parents")).intValue();

        var payload = JSON.createObjectNode();
        payload.put("documentId", documentId.toString());
        payload.put("bodySnapshotId", bodySnapshotId.toString());
        payload.put("embeddingModel", MOCK_EMBEDDING_MODEL);
        payload.put("dimension", MOCK_EMBEDDING_DIMENSION);
        // putArray 返回 ArrayNode 本体；addObject().put(...) 链式填充单个 generation 条目。
        payload.putArray("generations").addObject()
                .put("generationId", MOCK_GENERATION_ID.toString())
                .put("indexedRows", total)
                .put("embeddedRows", embedded);
        AiJobService.ResultsOutcome outcome =
                aiJobs.submitIndexResult(jobId, epoch, lease.leaseToken(), payload);
        log.info("[worker-mock] 任务 {} 推进至 {}（indexedRows={} embeddedRows={}）",
                jobId, outcome.status(), total, embedded);
    }

    // ---- 对账表 ----

    /**
     * 每篇语料的 {@code document_body_chunk} 行数 vs 任务写入行数对账。
     * {@code SUCCEEDED} 蕴含 Java 侧已校验 indexedRows == 正文行数（AiJobService.handleIndexDocumentResult）；
     * embeddedRows 期望值 = 非 PARENT 行数（worker 只向量化非 PARENT 行）。
     */
    private List<ReconcileRow> reconcile(List<CorpusDoc> corpus, Map<String, List<UUID>> jobIdsByFile,
            Map<UUID, AiJobStatus> statuses) {
        List<ReconcileRow> rows = new ArrayList<>();
        for (CorpusDoc doc : corpus) {
            Integer total = jdbc.queryForObject(
                    "SELECT count(*) FROM document_body_chunk WHERE document_id=?", Integer.class, doc.documentId());
            Integer parents = jdbc.queryForObject(
                    "SELECT count(*) FROM document_body_chunk WHERE document_id=? "
                            + "AND metadata->>'chunkType'='PARENT'", Integer.class, doc.documentId());
            String documentStatus = jdbc.query(
                    "SELECT status FROM project_document WHERE id=? AND project_id=?",
                    (rs, row) -> rs.getString(1), doc.documentId(), E2_PROJECT_ID)
                    .stream().findFirst().orElse("MISSING");
            List<UUID> jobs = jobIdsByFile.getOrDefault(doc.filename(), List.of());
            List<String> jobStatusText = new ArrayList<>();
            for (UUID jobId : jobs) {
                AiJobStatus status = statuses.get(jobId);
                jobStatusText.add(status == null ? "UNKNOWN" : status.name());
            }
            rows.add(new ReconcileRow(doc, total == null ? 0 : total, parents == null ? 0 : parents,
                    (total == null ? 0 : total) - (parents == null ? 0 : parents),
                    documentStatus, jobStatusText, !jobs.isEmpty(), ""));
        }
        return rows;
    }

    private void printReconciliation(List<ReconcileRow> rows) {
        StringBuilder table = new StringBuilder("\n---- E2 索引对账表（document_body_chunk 行数 vs 任务写入）----\n");
        table.append(String.format(Locale.ROOT, "%-42s %-8s %-6s %-6s %-9s %-9s %s%n",
                "filename", "rows", "parent", "embedd", "docStatus", "jobStatus", "documentId"));
        for (ReconcileRow row : rows) {
            table.append(String.format(Locale.ROOT, "%-42s %-8d %-6d %-6d %-9s %-9s %s%n",
                    row.doc().filename(), row.bodyRows(), row.parentRows(), row.embeddedExpected(),
                    row.documentStatus(), String.join(",", row.jobStatuses()), row.doc().documentId()));
        }
        table.append("---- 对账结束（embeddedRows 期望 = 非 PARENT 行数；SUCCEEDED 蕴含 indexedRows == rows）----");
        log.info("{}", table);
    }

    // ---- 工具 ----

    /**
     * RFC 4122 v5（SHA-1 命名空间版）：namespace 的 16 字节大端 + name 的 UTF-8 字节一起 SHA-1，
     * 取前 16 字节，置 version=5、variant=RFC4122。与 Python {@code uuid.uuid5} 逐字节一致。
     */
    static UUID uuid5(UUID namespace, String name) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(namespace.getMostSignificantBits());
        buffer.putLong(namespace.getLeastSignificantBits());
        byte[] hash = sha1Digest(buffer.array(), name.getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        long most = 0L;
        long least = 0L;
        for (int index = 0; index < 8; index++) {
            most = (most << 8) | (hash[index] & 0xffL);
            least = (least << 8) | (hash[index + 8] & 0xffL);
        }
        return new UUID(most, least);
    }

    private static byte[] sha1Digest(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (Exception failure) {
            throw new IllegalStateException("JDK 缺少 SHA-1", failure);
        }
    }

    private static String sha256Hex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception failure) {
            throw new IllegalStateException("无法计算语料哈希", failure);
        }
    }

    private static boolean workerMockEnabled() {
        String value = env("E2_INDEX_WORKER_MOCK");
        return value == null || value.isBlank() || Boolean.parseBoolean(value.trim());
    }

    private static boolean clearRequested() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("E2_INDEX_CLEAR", "false").trim());
    }

    private static long waitTimeoutSeconds() {
        String value = env("E2_INDEX_WAIT_TIMEOUT_SECONDS");
        if (value == null || value.isBlank()) {
            return 600L;
        }
        return Long.parseLong(value.trim());
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
