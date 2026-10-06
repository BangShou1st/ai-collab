package com.shitulelv.aicollab.document.infrastructure;

import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
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
class DocumentRepositoryIntegrationTest {
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
    @Autowired com.shitulelv.aicollab.document.application.service.DocumentContentService content;
    @Autowired com.shitulelv.aicollab.work.application.service.TaskApplicationService tasks;

    @Test
    void searchReadsJsonMetadataIntoTheResultView() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                userId, "document-" + userId.toString().substring(0, 8),
                "test-only-hash", "Document owner");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                projectId, "Document search", userId, userId);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                projectId, userId);
        jdbc.update("""
                INSERT INTO project_document(
                    id, project_id, display_name, original_filename, mime_type,
                    size_bytes, object_key, status, chunk_count,
                    embedding_provider, embedding_model, embedding_dimension, uploaded_by)
                VALUES (?, ?, '需求', '需求.pdf', 'application/pdf',
                    10, ?, 'READY', 1, 'test-provider', 'test-model', 3, ?)
                """, documentId, projectId, "projects/" + projectId + "/requirements.pdf", userId);
        documents.replaceChunks(
                projectId,
                documentId,
                List.of(new DocumentChunk(
                        0, "验收", "必须通过全部自动化测试", "hash", 8,
                        Map.of("pageNumber", 7))),
                List.of(List.of(1d, 0d, 0d)),
                "test-provider",
                "test-model",
                3,
                "需求.pdf");

        var result = documents.search(
                projectId, List.of(1d, 0d, 0d),
                "test-provider", "test-model", 3,
                com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints
                        .fingerprint("test-provider", "test-model", 3),
                List.of(documentId), 8);

        assertThat(result).singleElement().satisfies(hit -> {
            assertThat(hit.originalFilename()).isEqualTo("需求.pdf");
            assertThat(hit.metadata()).containsEntry("pageNumber", 7);
        });
        UUID token=UUID.randomUUID();
        jdbc.update("UPDATE project_document SET status='PARSING',processing_token=? WHERE id=?",token,documentId);
        var bodyChunks=new com.shitulelv.aicollab.document.domain.service.DocumentChunker().split("# 验收\n"+"正文。".repeat(1000));
        content.saveParsed(projectId,documentId,token,"原件".getBytes(java.nio.charset.StandardCharsets.UTF_8),bodyChunks);
        jdbc.update("UPDATE project_document SET status='FAILED',error_message='向量化失败' WHERE id=?",documentId);
        var state=content.status(projectId,documentId,userId);
        assertThat(state.path("bodyReadable").asBoolean()).isTrue();
        assertThat(state.path("searchAvailable").asBoolean()).isFalse();
        var read=content.read(projectId,documentId,userId,null,0,0,100,null,null);
        assertThat(read.path("readChars").asInt()).isEqualTo(100);
        assertThat(read.path("hasMore").asBoolean()).isTrue();
        var cursor=read.path("continuation");
        var continued=content.read(projectId,documentId,userId,UUID.fromString(cursor.path("snapshotId").asText()),cursor.path("fromChunk").asInt(),cursor.path("fromOffset").asInt(),100,null,null);
        assertThat(continued.path("items").get(0).path("fromOffset").asInt()).isEqualTo(100);
        UUID oldChunk=UUID.fromString(read.path("items").get(0).path("chunkId").asText());
        UUID oldSnapshot=UUID.fromString(state.path("snapshotId").asText());
        jdbc.update("UPDATE project_document SET status='PARSING' WHERE id=?",documentId);
        content.saveParsed(projectId,documentId,token,"新原件".getBytes(java.nio.charset.StandardCharsets.UTF_8),bodyChunks);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->content.read(projectId,documentId,userId,oldSnapshot,0,0,100,null,null)).hasMessageContaining("来源已更新");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->content.read(projectId,documentId,userId,null,0,0,100,null,oldChunk)).hasMessageContaining("引用来源已更新或已删除");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->content.status(UUID.randomUUID(),documentId,userId)).isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);
        // At least 100 persisted tasks, stable keyset continuation, exact keyword beyond item 50.
        for(int i=1;i<=110;i++) jdbc.update("INSERT INTO project_task(id,project_id,title,description,status,priority,created_by) VALUES (?,?,?,'','TODO','MEDIUM',?)",new UUID(0,i),projectId,i==90?"第九十项目标":"任务"+i,userId);
        var tool=new com.shitulelv.aicollab.agent.infrastructure.tool.TaskListAgentTool(tasks,new com.fasterxml.jackson.databind.ObjectMapper());
        var ctx=new com.shitulelv.aicollab.agent.domain.tool.AgentToolContext(UUID.randomUUID(),projectId,userId,"OWNER",false,0);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var first=tool.execute(ctx,json.createObjectNode().put("limit",50)).data();
        assertThat(first.path("total").asInt()).isEqualTo(110);
        assertThat(first.path("items").size()).isLessThanOrEqualTo(50);
        assertThat(first.path("returned").asInt()).isEqualTo(first.path("items").size());
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        // 按 nextCursor 续读（含该记录本身）直到最后一页：完整覆盖、不重复、不遗漏
        var seen=new java.util.LinkedHashSet<String>();
        var titles=new java.util.ArrayList<String>();
        var page=first;
        int pages=0;
        while(page.path("hasMore").asBoolean() && pages<40){
            for(var item:page.path("items")){ seen.add(item.path("id").asText()); titles.add(item.path("title").asText()); }
            page=tool.execute(ctx,json.createObjectNode().put("limit",50)
                    .put("cursor",page.path("nextCursor").asText())).data();
            pages++;
        }
        for(var item:page.path("items")){ seen.add(item.path("id").asText()); titles.add(item.path("title").asText()); }
        assertThat(seen).hasSize(110);
        assertThat(titles).filteredOn("第九十项目标"::equals).hasSize(1);
        assertThat(seen).contains(new UUID(0,90).toString());
        var found=tool.execute(ctx,json.createObjectNode().put("query","第九十项目标")).data();
        assertThat(found.path("items")).hasSize(1);
        jdbc.update("DELETE FROM project_document WHERE id=?",documentId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_body_chunk WHERE document_id=?",Integer.class,documentId)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->content.read(projectId,documentId,userId,null,0,0,100,null,oldChunk)).isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);
    }
}
