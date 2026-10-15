package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRecoveryCandidate;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档恢复的数据库语义回归：recoverable 查询条件、UPLOADED 可恢复、过期 PARSING / INDEXING
 * 经 failStale 迁移为 FAILED。定时恢复在此测试中被推迟，避免后台线程干扰断言。
 */
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
        "storage.minio.bucket=test-bucket",
        "document.dispatch-interval=3600s",
        "document.dispatch-initial-delay=3600s"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class DocumentRecoveryPostgresIntegrationTest {
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

    private UUID ownerId;
    private UUID projectId;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerId, "owner-" + ownerId.toString().substring(0, 8), "test-hash", "文档所有者");
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, name, owner_id, created_by) VALUES (?, ?, ?, ?)",
                projectId, "文档恢复", ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')",
                projectId, ownerId);
    }

    @Test
    void uploadedDocumentIsAlwaysRecoverable() {
        UUID documentId = insertDocument(DocumentStatus.UPLOADED, null, null);

        List<DocumentRecoveryCandidate> candidates = documents.recoverable(staleBefore());

        assertThat(candidates)
                .filteredOn(candidate -> candidate.documentId().equals(documentId))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.projectId()).isEqualTo(projectId);
                    assertThat(candidate.status()).isEqualTo(DocumentStatus.UPLOADED);
                    assertThat(candidate.processingToken()).isNull();
                });
    }

    @Test
    void readyAndFreshProcessingDocumentsAreNotRecoverable() {
        insertDocument(DocumentStatus.READY, UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
        insertDocument(DocumentStatus.PARSING, UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
        insertDocument(DocumentStatus.PARSING, null, null);
        insertDocument(DocumentStatus.INDEXING, UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(documents.recoverable(staleBefore())).isEmpty();
    }

    @Test
    void staleIndexingDocumentIsFailedThroughFailStale() {
        UUID processingToken = UUID.randomUUID();
        UUID documentId = insertDocument(DocumentStatus.INDEXING, processingToken,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));
        OffsetDateTime staleBefore = staleBefore();

        DocumentRecoveryCandidate candidate = documents.recoverable(staleBefore).stream()
                .filter(entry -> entry.documentId().equals(documentId))
                .findFirst()
                .orElseThrow();
        assertThat(candidate.status()).isEqualTo(DocumentStatus.INDEXING);
        assertThat(candidate.processingToken()).isEqualTo(processingToken);

        assertThat(documents.failStale(candidate, staleBefore)).isTrue();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, error_message, processing_token, processing_heartbeat_at "
                        + "FROM project_document WHERE id = ?", documentId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("processing_token")).isNull();
        assertThat(row.get("processing_heartbeat_at")).isNull();
        assertThat(row.get("error_message")).isNotNull();
        assertThat(documents.recoverable(staleBefore)).isEmpty();
    }

    private OffsetDateTime staleBefore() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(15);
    }

    private UUID insertDocument(DocumentStatus status, UUID processingToken, OffsetDateTime heartbeat) {
        UUID documentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_document(
                    id, project_id, display_name, original_filename, mime_type,
                    size_bytes, object_key, status, chunk_count, uploaded_by,
                    processing_token, processing_heartbeat_at)
                VALUES (?, ?, '需求', 'requirements.txt', 'text/plain',
                    16, ?, ?, 0, ?, ?, ?)
                """,
                documentId, projectId,
                "projects/" + projectId + "/documents/" + documentId + "/source.txt",
                status.name(), ownerId, processingToken,
                heartbeat == null ? null : Timestamp.from(heartbeat.toInstant()));
        return documentId;
    }
}
