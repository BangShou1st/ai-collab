package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.retrieval.RetrievalCoverage.ExpectedEvidence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
    private static final Path EVIDENCE_PATH =
            Path.of("..", "docs", "acceptance-evidence", "2026-10-16", "d-rag-01a",
                    "deterministic-baseline.json");

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

    @AfterAll
    static void writeEvidence() throws Exception {
        Files.createDirectories(EVIDENCE_PATH.getParent());
        // LinkedHashMap 而非 Map.of：证据文件的键顺序也要确定性，便于逐次比对 diff。
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("note", "D-RAG-01A 确定性检索基线的机器可读诊断数据。pgvector 检索、"
                + "指纹/代际过滤、知识上下文构建均为真实实现；嵌入向量为 SyntheticEmbedder 合成，"
                + "因此不代表模型召回质量，也不代表模型回答质量。");
        document.put("topKObservations", List.of(TOP_8, TOP_20));
        document.put("knowledgeQaContextLimit", QA_LIMIT);
        document.put("agentContextLimit", AGENT_LIMIT);
        document.put("cases", EVIDENCE);
        Files.writeString(EVIDENCE_PATH,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document),
                StandardCharsets.UTF_8);
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
    void identicalContentAcrossDocumentsIsRecalledButDeduplicatedInContext() {
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

        // 两条都进了向量候选（数据库召回到），但上下文按 contentHash 去重只保留一条。
        assertThat(measured.get("retrievalRecall")).isEqualTo(1.0d);
        assertThat(measured.get("contextEvidenceRetention")).isEqualTo(0.5d);
        assertThat(measured.get("droppedByContext")).isEqualTo(1);
        assertThat(measured.get("retainedSourceCount")).isEqualTo(1);
    }

    // ---- 5. 无有效证据 ----

    @Test
    void unrelatedQueryProducesNoRetainedContextAndIsUndefinedNotFailed() {
        UUID document = insertDocument("无关件.md");
        insertChunk(document, 0, "无关", "quarterly catering menu rotation schedule");
        insertChunk(document, 1, "无关", "annual cafeteria seating arrangement plan");

        List<DocumentSearchHit> candidates = runSearch(
                "krypton xenon argon spectroscopy", null, List.of(document), TOP_8);
        KnowledgeContext context = builder.build(candidates, AGENT_LIMIT);
        // 语料里本来就没有可回答该问题的证据，所以期望证据集合为空——
        // 不能凭空造一条“期望证据”把未定义的 0/0 伪装成检索失败 0.0。
        var measured = RetrievalCoverage.measure(TOP_8, AGENT_LIMIT, List.of(), candidates, context);

        // pgvector 仍会按相似度返回若干行，但没有一条达到相似度下限：
        // 于是上下文为空，Recall/Retention 未定义（不是 0，也不是失败）。
        assertThat(context.sources()).isEmpty();
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
        KnowledgeContext qaContext = builder.build(candidates);
        KnowledgeContext agentContext = builder.build(candidates, AGENT_LIMIT);

        var qa = RetrievalCoverage.measure(TOP_20, QA_LIMIT, expected, candidates, qaContext);
        var agent = RetrievalCoverage.measure(TOP_20, AGENT_LIMIT, expected, candidates, agentContext);
        record("来源上限-知识问答", qa);
        record("来源上限-Agent", agent);

        assertThat(qaContext.sources().size()).isEqualTo(5);
        assertThat(agentContext.sources().size()).isEqualTo(12);
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
        KnowledgeContext agentContext = builder.build(candidates, AGENT_LIMIT);
        var measured = RetrievalCoverage.measure(TOP_20, AGENT_LIMIT, expected, candidates, agentContext);
        record("预算" , measured);

        // 允许 12 个来源不等于能塞进 12 条长正文：正文预算仍是硬上限。
        assertThat(agentContext.sources().size()).isLessThan(AGENT_LIMIT);
        assertThat(agentContext.promptSources().codePointCount(0, agentContext.promptSources().length()))
                .isLessThanOrEqualTo(8000);
    }

    // ---- 执行与记录 ----

    private Map<String, Object> runCase(
            String caseName, int topK, String query, List<UUID> scopedDocuments,
            List<UUID> requestedDocuments, List<ExpectedEvidence> expected) {
        List<UUID> requested = requestedDocuments == null ? scopedDocuments : requestedDocuments;
        List<DocumentSearchHit> candidates = runSearch(query, null, requested, topK);
        KnowledgeContext context = builder.build(candidates, AGENT_LIMIT);
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
