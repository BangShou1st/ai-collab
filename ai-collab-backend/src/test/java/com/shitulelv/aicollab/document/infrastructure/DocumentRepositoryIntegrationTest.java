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
                "test-provider", "test-model", 3, List.of(documentId), 8);

        assertThat(result).singleElement().satisfies(hit -> {
            assertThat(hit.originalFilename()).isEqualTo("需求.pdf");
            assertThat(hit.metadata()).containsEntry("pageNumber", 7);
        });
    }
}
