package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class DocumentRegistrationStatusIntegrationTest {

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
    DocumentRegistrationService registration;

    private UUID ownerId;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        ownerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerId, "owner-" + ownerId.toString().substring(0, 8), "test-hash", "文档所有者");
    }

    @Test
    void preparingProjectAcceptsDocumentRegistration() {
        UUID projectId = insertProject("PREPARING");
        DocumentEntity document = document(projectId);

        registration.registerUploadedDocument(document);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE project_id = ? AND id = ?",
                Integer.class, projectId, document.getId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void completedProjectReturnsReadOnlyInsteadOfNotFound() {
        UUID projectId = insertProject("COMPLETED");

        assertThatThrownBy(() -> registration.registerUploadedDocument(document(projectId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PROJECT_READ_ONLY));
    }

    @Test
    void missingProjectStillReturnsNotFound() {
        assertThatThrownBy(() -> registration.registerUploadedDocument(document(UUID.randomUUID())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));
    }

    private UUID insertProject(String status) {
        UUID projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id, name, owner_id, status, created_by)
                VALUES (?, '文档状态测试', ?, ?, ?)
                """, projectId, ownerId, status, ownerId);
        jdbc.update("""
                INSERT INTO project_member(project_id, user_id, role)
                VALUES (?, ?, 'OWNER')
                """, projectId, ownerId);
        return projectId;
    }

    private DocumentEntity document(UUID projectId) {
        UUID documentId = UUID.randomUUID();
        DocumentEntity document = new DocumentEntity();
        document.setId(documentId);
        document.setProjectId(projectId);
        document.setDisplayName("需求说明");
        document.setOriginalFilename("requirements.txt");
        document.setMimeType("text/plain");
        document.setSizeBytes(16L);
        document.setObjectKey("projects/" + projectId + "/documents/" + documentId + "/source.txt");
        document.setStatus(DocumentStatus.UPLOADED);
        document.setChunkCount(0);
        document.setUploadedBy(ownerId);
        return document;
    }
}
