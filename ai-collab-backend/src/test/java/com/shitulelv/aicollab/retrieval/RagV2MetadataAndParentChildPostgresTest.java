package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.retrieval.pipeline.ParentChildExpander;
import com.shitulelv.aicollab.retrieval.pipeline.PostgresDocumentSectionReader;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.SentenceWindowExpander;
import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
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
class RagV2MetadataAndParentChildPostgresTest {

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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DocumentRepository documents;

    @Autowired
    DocumentChunker chunker;

    @Autowired
    ParentChildExpander parentChildExpander;

    @Autowired
    SentenceWindowExpander sentenceWindowExpander;

    @Autowired
    PostgresDocumentSectionReader sectionReader;

    private UUID createTestUserAndProject() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                userId, "user-" + userId.toString().substring(0, 8),
                "test-password-hash", "RAG Tester");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                projectId, "RAG V2 Project", userId, userId);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                projectId, userId);
        return projectId;
    }

    private UUID insertDocument(UUID projectId, String filename, String mimeType, String tag) {
        UUID docId = UUID.randomUUID();
        UUID ownerId = jdbc.queryForObject("SELECT owner_id FROM project WHERE id = ?", UUID.class, projectId);
        jdbc.update("""
                INSERT INTO project_document(
                    id, project_id, display_name, original_filename, mime_type,
                    size_bytes, object_key, status, chunk_count,
                    embedding_provider, embedding_model, embedding_dimension, uploaded_by)
                VALUES (?, ?, ?, ?, ?, 1024, ?, 'READY', 1, 'test-prov', 'test-model', 3, ?)
                """, docId, projectId, filename, filename, mimeType, "obj/" + docId, ownerId);
        return docId;
    }

    @Test
    @DisplayName("测试真实 Parent-Child 父子持久化、外键约束、初筛排除父块与上下文回读")
    void testRealParentChildPersistenceAndTopKExclusion() {
        UUID projectId = createTestUserAndProject();
        UUID documentId = insertDocument(projectId, "微服务架构长章.md", "text/markdown", "arch");

        // 构造一个长度 > 1200 字符的长章节
        String proseParagraph = "微服务架构治理与拆分原则。服务边界划分必须清晰明确。系统间解耦依赖消息队列与异步通信。"
                + "分布式事务必须基于最终一致性Saga或TCC模式设计。高并发场景必须配备熔断限流机制。服务治理监控指标应当覆盖P99延迟。\n\n";
        String fullProse = "# 微服务架构设计规范\n" + proseParagraph.repeat(25);

        List<DocumentChunk> chunks = chunker.split(fullProse);
        assertThat(chunks).hasSizeGreaterThan(2);

        DocumentChunk parentChunk = chunks.getFirst();
        assertThat(parentChunk.chunkType()).isEqualTo(DocumentChunk.TYPE_PARENT);
        assertThat(parentChunk.id()).isNotNull();
        assertThat(parentChunk.parentChunkId()).isNull();

        UUID parentId = parentChunk.id();
        for (int i = 1; i < chunks.size(); i++) {
            DocumentChunk child = chunks.get(i);
            assertThat(child.chunkType()).isEqualTo(DocumentChunk.TYPE_CHILD);
            assertThat(child.parentChunkId()).isEqualTo(parentId);
        }

        // 构建对应的虚拟向量并写入数据库（先写父后写子）
        List<List<Double>> vectors = chunks.stream()
                .map(c -> List.of(1.0, 0.0, 0.0))
                .toList();

        documents.replaceChunks(
                projectId,
                documentId,
                chunks,
                vectors,
                "test-prov",
                "test-model",
                3,
                "微服务架构长章.md"
        );

        // 验证数据库真实持久化结果
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, chunk_no, chunk_type, parent_chunk_id FROM document_chunk WHERE document_id = ? ORDER BY chunk_no ASC",
                documentId
        );
        assertThat(rows).hasSize(chunks.size());

        Map<String, Object> parentRow = rows.getFirst();
        assertThat(parentRow.get("id")).isEqualTo(parentId);
        assertThat(parentRow.get("chunk_type")).isEqualTo(DocumentChunk.TYPE_PARENT);
        assertThat(parentRow.get("parent_chunk_id")).isNull();

        for (int i = 1; i < rows.size(); i++) {
            Map<String, Object> childRow = rows.get(i);
            assertThat(childRow.get("chunk_type")).isEqualTo(DocumentChunk.TYPE_CHILD);
            assertThat(childRow.get("parent_chunk_id")).isEqualTo(parentId);
        }

        // 验证向量检索 search 与词项检索 searchLexical 严格排除父块，绝不污染 TopK
        List<DocumentSearchHit> denseHits = documents.search(
                projectId,
                List.of(1.0, 0.0, 0.0),
                "test-prov",
                "test-model",
                3,
                com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints.fingerprint("test-prov", "test-model", 3),
                List.of(documentId),
                10
        );
        assertThat(denseHits).isNotEmpty();
        for (DocumentSearchHit hit : denseHits) {
            assertThat(hit.id()).isNotEqualTo(parentId);
        }

        List<DocumentSearchHit> lexicalHits = documents.searchLexical(
                projectId,
                "微服务架构治理",
                List.of(documentId),
                10,
                null
        );
        assertThat(lexicalHits).isNotEmpty();
        for (DocumentSearchHit hit : lexicalHits) {
            assertThat(hit.id()).isNotEqualTo(parentId);
        }

        // 验证 ParentChildExpander 从数据库通过 parentChunkId 成功回读父块上下文
        RetrievalCandidate childCandidate = RetrievalCandidate.fromDenseHit(denseHits.getFirst(), 1);
        List<RetrievalCandidate> expanded = parentChildExpander.expand(projectId, List.of(childCandidate));
        assertThat(expanded).hasSize(1);
        RetrievalCandidate expandedCand = expanded.getFirst();
        assertThat(expandedCand.metadata()).containsEntry("parentExpanded", true);
        assertThat(expandedCand.metadata()).containsEntry("parentChunkId", parentId.toString());
        assertThat(String.valueOf(expandedCand.metadata().get("parentContext"))).contains("微服务架构治理与拆分原则");
    }

    @Test
    @DisplayName("测试数据库硬过滤下推防 TopK 漏召回反例（干扰项数量超过 TopK）")
    void testDatabaseMetadataHardFilteringAntiTopKDrop() {
        UUID projectId = createTestUserAndProject();
        int topK = 3;

        // 1. 创建目标文档与分块
        UUID targetDocId = insertDocument(projectId, "安全生产红线与应急预案.pdf", "application/pdf", "security");
        DocumentChunk targetChunk = new DocumentChunk(
                0,
                "红线条款",
                "生产环境严禁明文存储高危密钥与证书，必须接入保密管理中台。违反即触发最高级告警。",
                "target-hash",
                50,
                Map.of("tags", List.of("security_redline", "core_compliance"))
        );
        documents.replaceChunks(
                projectId,
                targetDocId,
                List.of(targetChunk),
                List.of(List.of(0.9, 0.1, 0.0)),
                "test-prov",
                "test-model",
                3,
                "安全生产红线与应急预案.pdf"
        );

        // 2. 创建 8 个干扰文档（数量远超过 topK=3），具有完全相同的词项与高相似度向量
        for (int i = 1; i <= 8; i++) {
            UUID distractorDocId = insertDocument(projectId, "例行业务日常通报_" + i + ".docx", "application/vnd.word", "daily_report");
            DocumentChunk distractorChunk = new DocumentChunk(
                0,
                "日常汇报",
                "生产环境严禁明文存储高危密钥例行排查第 " + i + " 期，今日无异常事项发生。",
                "distractor-hash-" + i,
                50,
                Map.of("tags", List.of("general_notice"))
            );
            documents.replaceChunks(
                    projectId,
                    distractorDocId,
                    List.of(distractorChunk),
                    List.of(List.of(0.9, 0.1, 0.0)),
                    "test-prov",
                    "test-model",
                    3,
                    "例行业务日常通报_" + i + ".docx"
            );
        }

        // 3. 构建元数据硬过滤：指定仅检索 PDF 或仅检索文件名包含 "安全生产红线" 的文档
        DocumentFilterCriteria filenameCriteria = DocumentFilterCriteria.fromFilters(List.of(
                MetadataFilter.contains("originalFilename", "安全生产红线")
        ));

        List<DocumentSearchHit> filteredHits = documents.search(
                projectId,
                List.of(0.9, 0.1, 0.0),
                "test-prov",
                "test-model",
                3,
                com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints.fingerprint("test-prov", "test-model", 3),
                null,
                topK,
                null,
                filenameCriteria
        );

        // 证明：若没有过滤，8个干扰项会抢占 topK 导致目标被挤出；而数据库下推过滤后，准确召回目标文档！
        assertThat(filteredHits).isNotEmpty();
        assertThat(filteredHits.getFirst().documentId()).isEqualTo(targetDocId);
        assertThat(filteredHits.getFirst().originalFilename()).isEqualTo("安全生产红线与应急预案.pdf");

        // 4. 验证标签过滤 tagContains 下推
        DocumentFilterCriteria tagCriteria = DocumentFilterCriteria.fromFilters(List.of(
                MetadataFilter.contains("tags", "security_redline")
        ));
        List<DocumentSearchHit> tagHits = documents.search(
                projectId,
                List.of(0.9, 0.1, 0.0),
                "test-prov",
                "test-model",
                3,
                com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints.fingerprint("test-prov", "test-model", 3),
                null,
                topK,
                null,
                tagCriteria
        );
        assertThat(tagHits).hasSize(1);
        assertThat(tagHits.getFirst().documentId()).isEqualTo(targetDocId);

        // 5. 验证词项检索 searchLexical 硬过滤下推
        List<DocumentSearchHit> lexicalHits = documents.searchLexical(
                projectId,
                "生产环境严禁明文存储",
                null,
                topK,
                null,
                filenameCriteria
        );
        assertThat(lexicalHits).hasSize(1);
        assertThat(lexicalHits.getFirst().documentId()).isEqualTo(targetDocId);
    }

    @Test
    @DisplayName("测试 SentenceWindowExpander 结合 PostgresDocumentSectionReader 真正实现跨 Chunk 边界恢复句子上下文")
    void testCrossChunkSentenceWindowRecovery() {
        UUID projectId = createTestUserAndProject();
        UUID documentId = insertDocument(projectId, "双活容灾方案.md", "text/markdown", "disaster_recovery");

        String heading = "容灾切换方案";
        // 章节四句话分布在两个相邻 Chunk：
        // Chunk 0: 句子 0、句子 1
        // Chunk 1: 句子 2、句子 3
        String sentence0 = "第一句系统概述容灾切换的背景与触发机制。";
        String sentence1 = "第二句主备机房双活部署与流量动态调度策略。";
        String sentence2 = "第三句数据库增量数据秒级双向同步协议与防环机制。";
        String sentence3 = "第四句全链路异常熔断与自动故障转移演练规范。";

        String chunk0Content = sentence0 + sentence1;
        String chunk1Content = sentence2 + sentence3;

        DocumentChunk c0 = new DocumentChunk(0, heading, chunk0Content, "hash-0", 20, Map.of("charFrom", 0, "charThrough", chunk0Content.length()));
        DocumentChunk c1 = new DocumentChunk(1, heading, chunk1Content, "hash-1", 20, Map.of("charFrom", chunk0Content.length(), "charThrough", chunk0Content.length() + chunk1Content.length()));

        documents.replaceChunks(
                projectId,
                documentId,
                List.of(c0, c1),
                List.of(List.of(1.0, 0.0, 0.0), List.of(0.0, 1.0, 0.0)),
                "test-prov",
                "test-model",
                3,
                "双活容灾方案.md"
        );

        // 命中 Chunk 1（仅包含句子 2 和句子 3）
        RetrievalCandidate candidate = new RetrievalCandidate(
                UUID.randomUUID(),
                documentId,
                "双活容灾方案.md",
                heading,
                chunk1Content,
                "hash-1",
                Map.of("charFrom", chunk0Content.length(), "charThrough", chunk0Content.length() + chunk1Content.length()),
                1,
                0.95,
                null,
                null,
                0.05,
                0.9,
                "test"
        );

        // 窗口大小设为 1，期望跨越 Chunk 边界，向前恢复来自 Chunk 0 的句子 1！
        List<RetrievalCandidate> expanded = sentenceWindowExpander.expand(projectId, List.of(candidate), 1);

        assertThat(expanded).hasSize(1);
        RetrievalCandidate result = expanded.getFirst();

        // 验证跨 Chunk 恢复的扩展文本：
        // 应该包含来自前一个 Chunk (Chunk 0) 的句子 1，以及当前的句子 2 与句子 3！
        String expandedText = result.expandedContent();
        assertThat(expandedText).isNotNull();
        assertThat(expandedText).contains(sentence1); // 跨 Chunk 边界恢复成功！
        assertThat(expandedText).contains(sentence2);
        assertThat(expandedText).contains(sentence3);

        // 不应过度展开到更前一级的句子 0 (因 windowSize = 1)
        assertThat(expandedText).doesNotContain(sentence0);

        // 契约保护校验：原始 candidate 的 content 和证据坐标完全不受损害
        assertThat(result.content()).isEqualTo(chunk1Content);
        assertThat(result.metadata().get("charFrom")).isEqualTo(chunk0Content.length());
        assertThat(result.metadata().get("charThrough")).isEqualTo(chunk0Content.length() + chunk1Content.length());
    }
}
