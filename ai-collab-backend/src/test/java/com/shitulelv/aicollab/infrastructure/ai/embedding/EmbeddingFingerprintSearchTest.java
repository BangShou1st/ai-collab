package com.shitulelv.aicollab.infrastructure.ai.embedding;

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
class EmbeddingFingerprintSearchTest {
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

    private UUID seedDocument(String userTag, String projectName) {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "fp-" + userTag + "-" + user.toString().substring(0, 8), "test-only-hash", "Fp");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, projectName, user, user);
        jdbc.update("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,status,chunk_count,uploaded_by)
                VALUES (?,?,'d','d.pdf','application/pdf',10,?,'READY',1,?)
                """, document, project, "projects/" + project + "/d.pdf", user);
        documents.replaceChunks(project, document,
                List.of(new DocumentChunk(0, "h", "content", "hash", 8, Map.of())),
                List.of(List.of(1d, 0d, 0d)), "test-provider", "test-model", 3, "d.pdf");
        return document;
    }

    @Test
    void same_fingerprint_is_searched() {
        UUID document = seedDocument("same", "SameSpace");
        UUID project = jdbc.queryForObject("SELECT project_id FROM project_document WHERE id=?",
                UUID.class, document);
        String fingerprint = EmbeddingFingerprints.fingerprint("test-provider", "test-model", 3);

        var hits = documents.search(project, List.of(1d, 0d, 0d),
                "test-provider", "test-model", 3, fingerprint, List.of(document), 8);

        assertThat(hits).hasSize(1);
    }

    @Test
    void mixed_fingerprint_is_never_searched() {
        UUID document = seedDocument("mixed", "MixedSpace");
        UUID project = jdbc.queryForObject("SELECT project_id FROM project_document WHERE id=?",
                UUID.class, document);
        String other = EmbeddingFingerprints.fingerprint("other-provider", "other-model", 7);

        var hits = documents.search(project, List.of(1d, 0d, 0d),
                "test-provider", "test-model", 3, other, List.of(document), 8);

        assertThat(hits).isEmpty();
    }
}
