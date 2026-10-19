package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.retrieval.RetrievalCoverage.ExpectedEvidence;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * D-RAG-01C 第二轮 阶段 B1：<b>普通 CI 的确定性检索质量硬门槛</b>。
 *
 * <p>本类<b>不门控、不联网、不调用模型</b>：用真实 PostgreSQL + pgvector 与 {@link SyntheticEmbedder}
 * 合成向量，把“必须永远成立”的质量不变量钉成 CI 硬门槛，整类被跳过即判失败（见 ci.yml 守卫）。
 * 它回答“检索与上下文构建管线的正确性有没有退化”，<b>不</b>回答“语义召回质量好不好”——
 * 后者是真实 Ollama 的 Opt-in 门槛（{@code AgentRetrievalQualityPostgresTest}）。</p>
 *
 * <p>覆盖的硬门槛：来源身份一致；指定范围不越界；指纹/代际双向隔离；丢弃归因合计等于
 * droppedByContext；上下文正文预算不超限；跨文档相同正文保留不同来源身份；默认知识问答入口
 * 不回归。工具失败不得当成功证据由 {@code AgentResearchQualityPostgresTest}（同样非门控、CI 内运行）
 * 钉住。</p>
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
class RetrievalQualityHardGatePostgresTest {
    private static final int TOP_20 = 20;
    private static final int AGENT_LIMIT = KnowledgeContextBuilder.MAX_SOURCES_LIMIT;
    private static final int BUDGET_CODE_POINTS = 8000;
    private static final String PROVIDER = "OLLAMA";
    private static final String MODEL = "synthetic-hard-gate";
    private static final int DIMENSION = SyntheticEmbedder.DIMENSIONS;
    private static final String FINGERPRINT =
            EmbeddingFingerprints.fingerprint(PROVIDER, MODEL, DIMENSION);

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

    // ---- 门槛 1：来源 documentId/chunkId 身份一致 ----

    @Test
    void retainedSourcesKeepConsistentDocumentAndChunkIdentity() {
        UUID document = insertDocument("身份一致性.md");
        UUID evidence = insertChunk(document, 0, "证据", "identity gate marker alpha unique evidence");
        insertUnrelatedNoise(document, 1, 4);

        List<DocumentSearchHit> candidates = runSearch("identity gate marker alpha", null, List.of(document), TOP_20);
        var context = builder.buildDiverseWithDiagnostics(candidates, AGENT_LIMIT).context();

        assertThat(context.sources()).extracting(KnowledgeSource::chunkId).contains(evidence);
        for (KnowledgeSource source : context.sources()) {
            assertThat(source.documentId())
                    .as("保留来源 %s 的 documentId 必须与其 chunkId 归属一致", source.chunkId())
                    .isEqualTo(document);
        }
    }

    // ---- 门槛 2：指定文档范围不越界 ----

    @Test
    void documentScopeNeverLeaksOutsideSelectedDocuments() {
        UUID inside = insertDocument("范围内.md");
        UUID outside = insertDocument("范围外.md");
        insertChunk(inside, 0, "范围", "scope gate marker sigma inside the scoped document");
        insertChunk(outside, 0, "范围", "scope gate marker sigma outside the scoped document");

        List<DocumentSearchHit> scoped = runSearch("scope gate marker sigma", null, List.of(inside), TOP_20);

        assertThat(scoped).extracting(DocumentSearchHit::documentId)
                .as("指定范围检索不得返回范围外文档").allMatch(inside::equals);
        // 反向对照：放开范围后范围外证据可见——证明生效的是范围而不是数据缺失。
        assertThat(runSearch("scope gate marker sigma", null, List.of(inside, outside), TOP_20))
                .extracting(DocumentSearchHit::documentId).contains(outside);
    }

    // ---- 门槛 3：指纹/代际隔离（双向） ----

    @Test
    void indexGenerationIsolationIsBidirectional() {
        UUID config = insertEmbeddingConfig();
        UUID retired = insertGeneration(config, "RETIRED");
        UUID active = insertGeneration(config, "ACTIVE");

        UUID document = insertDocument("代际门禁.md");
        UUID oldChunk = insertChunk(document, 0, "旧代", "generation gate marker omega", retired);
        UUID newChunk = insertChunk(document, 1, "新代", "generation gate marker omega", active);

        List<DocumentSearchHit> hits = runSearch("generation gate marker omega", active, List.of(document), TOP_20);
        assertThat(hits).extracting(DocumentSearchHit::id).containsExactly(newChunk).doesNotContain(oldChunk);
        // 反向：不带代际（NULL）检索看不到任何带代际的 chunk。
        assertThat(runSearch("generation gate marker omega", null, List.of(document), TOP_20)).isEmpty();
    }

    // ---- 门槛 4：丢弃归因互斥且合计等于 droppedByContext ----

    @Test
    void dropReasonsSumEqualsDroppedByContext() {
        UUID documentA = insertDocument("甲.md");
        UUID documentB = insertDocument("乙.md");
        // 同文档重复 + 跨文档同样正文 + 空正文 + 名额不足，一次构造多种丢弃原因。
        insertChunk(documentA, 0, "甲", "dedup gate marker same body", "same-hash");
        insertChunk(documentA, 1, "甲", "dedup gate marker same body duplicate", "same-hash");
        UUID b1 = insertChunk(documentB, 0, "乙", "dedup gate marker same body", "same-hash");
        insertChunk(documentA, 2, "甲", "dedup gate marker weak", "weak-hash");
        insertChunk(documentA, 3, "甲", "   ", "blank-hash");
        UUID surplus = insertChunk(documentB, 1, "乙", "dedup gate marker surplus", "surplus-hash");

        List<DocumentSearchHit> candidates = runSearch("dedup gate marker", null,
                List.of(documentA, documentB), TOP_20);
        var buildResult = builder.buildDiverseWithDiagnostics(candidates, 2);
        List<ExpectedEvidence> expected = List.of(
                new ExpectedEvidence(documentB, b1, "跨文档相同正文"),
                new ExpectedEvidence(documentB, surplus, "名额不足"));

        Map<String, Object> measured = RetrievalCoverage.measure(
                TOP_20, 2, expected, candidates, buildResult);

        int sum = number(measured, "crossDocumentDedupLoss") + number(measured, "intraDocumentDedupLoss")
                + number(measured, "sourceCapDrop") + number(measured, "budgetDrop")
                + number(measured, "belowSimilarityThresholdDrop") + number(measured, "emptyBodyDrop")
                + number(measured, "unattributedDrop");
        assertThat(sum).as("丢弃归因之和必须等于 droppedByContext，不得有无归因的静默丢弃")
                .isEqualTo(measured.get("droppedByContext"));
        assertThat(measured.get("droppedByContext")).as("该夹具必须真的产生丢弃，否则门槛形同虚设")
                .isEqualTo(1);
    }

    // ---- 门槛 5：上下文正文预算不超限 ----

    @Test
    void contextBodyBudgetIsNeverExceeded() {
        UUID document = insertDocument("预算门禁.md");
        List<DocumentSearchHit> candidates = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            insertChunk(document, i, "事实" + i, "budget gate item " + i + " " + "padding".repeat(400));
        }
        candidates.addAll(runSearch("budget gate item", null, List.of(document), TOP_20));

        KnowledgeContext diverse = builder.buildDiverseWithDiagnostics(candidates, AGENT_LIMIT).context();
        assertThat(diverse.promptSources().codePointCount(0, diverse.promptSources().length()))
                .isLessThanOrEqualTo(BUDGET_CODE_POINTS);
        assertThat(diverse.sources().size()).isLessThanOrEqualTo(AGENT_LIMIT);
    }

    // ---- 门槛 6：跨文档相同正文保留不同来源身份 ----

    @Test
    void crossDocumentIdenticalBodyKeepsDistinctSourceIdentity() {
        UUID documentA = insertDocument("合同甲.md");
        UUID documentB = insertDocument("合同乙.md");
        String body = "identical clause gate text across two documents";
        UUID chunkA = insertChunk(documentA, 0, "条款", body);
        UUID chunkB = insertChunk(documentB, 0, "条款", body);

        List<DocumentSearchHit> candidates =
                runSearch(body, null, List.of(documentA, documentB), TOP_20);
        KnowledgeContext context = builder.buildDiverseWithDiagnostics(candidates, AGENT_LIMIT).context();

        assertThat(context.sources()).extracting(KnowledgeSource::documentId)
                .contains(documentA, documentB);
        assertThat(context.sources()).extracting(KnowledgeSource::chunkId)
                .contains(chunkA, chunkB);
        // 身份不混淆：每个来源的 documentId 与其 chunkId 归属一致。
        for (KnowledgeSource source : context.sources()) {
            if (source.chunkId().equals(chunkA)) assertThat(source.documentId()).isEqualTo(documentA);
            if (source.chunkId().equals(chunkB)) assertThat(source.documentId()).isEqualTo(documentB);
        }
    }

    // ---- 门槛 7：默认知识问答 build 入口不回归 ----

    @Test
    void knowledgeQaDefaultEntryIsNotRegressed() {
        UUID document = insertDocument("知识问答.md");
        for (int i = 1; i <= 12; i++) {
            insertChunk(document, i, "事实" + i, "qa default gate item " + i + " unique token");
        }
        List<DocumentSearchHit> candidates = runSearch("qa default gate item unique token", null,
                List.of(document), TOP_20);

        KnowledgeContext qa = builder.build(candidates);
        assertThat(qa.sources()).as("默认知识问答入口仍为 5 个来源")
                .hasSize(KnowledgeContextBuilder.DEFAULT_MAX_SOURCES);
        assertThat(qa.sources()).extracting(KnowledgeSource::rank).containsExactly(1, 2, 3, 4, 5);
        assertThat(qa.promptSources().codePointCount(0, qa.promptSources().length()))
                .isLessThanOrEqualTo(BUDGET_CODE_POINTS);
    }

    // ---- 辅助 ----

    private static int number(Map<String, Object> item, String key) {
        return item.get(key) instanceof Integer value ? value : 0;
    }

    private List<DocumentSearchHit> runSearch(String query, UUID generationId,
                                              List<UUID> documentIds, int topK) {
        when(embeddings.embed(any(), anyList(), any())).thenReturn(new EmbeddingBatch(
                PROVIDER, MODEL, DIMENSION, List.of(SyntheticEmbedder.embed(query)),
                FINGERPRINT, generationId));
        return search.search(project, query, documentIds, topK);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, "hardgate-" + id.toString().substring(0, 8), "test-only-hash", "HardGate");
        return id;
    }

    private UUID insertProject() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                id, "硬门槛", user, user);
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
        return insertChunk(document, chunkNo, heading, content, null, null);
    }

    private UUID insertChunk(UUID document, int chunkNo, String heading, String content, String hash) {
        return insertChunk(document, chunkNo, heading, content, hash, null);
    }

    private UUID insertChunk(UUID document, int chunkNo, String heading, String content,
                             UUID generation) {
        return insertChunk(document, chunkNo, heading, content, null, generation);
    }

    private UUID insertChunk(UUID document, int chunkNo, String heading, String content,
                             String hash, UUID generation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,
                  content_hash,token_estimate,metadata,embedding_provider,embedding_model,
                  embedding_dimension,embedding_fingerprint,embedding,generation_id)
                VALUES (?,?,?,?,?,?,?,?,CAST('{}' AS jsonb),?,?,?,?,CAST(? AS vector),?)
                """, id, project, document, chunkNo, heading, content,
                hash != null ? hash : sha256(content), Math.max(1, content.length() / 4),
                PROVIDER, MODEL, DIMENSION, FINGERPRINT,
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
