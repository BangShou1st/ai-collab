package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.document.domain.model.DocumentDeletionJobStatus;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentDeletionJobRepository;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "agent.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "storage.minio.endpoint=http://127.0.0.1:19012",
        "storage.minio.access-key=synthetic-lane-a-access",
        "storage.minio.secret-key=synthetic-lane-a-secret",
        "storage.minio.bucket=lane-a-deletion",
        "document.dispatch-interval=3600s",
        "document.dispatch-initial-delay=3600s",
        "document.deletion.scheduler-enabled=false",
        "document.deletion.max-attempts=5"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class DocumentDeletionMinioOutageIntegrationTest {
    private static final String ACCESS = "synthetic-lane-a-access";
    private static final String SECRET = "synthetic-lane-a-secret";
    private static final String BUCKET = "lane-a-deletion";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Container
    static final FixedHostPortGenericContainer<?> MINIO =
            new FixedHostPortGenericContainer<>(MinioTestImage.IMAGE)
                    .withFixedExposedPort(19012, 9000)
                    .withEnv("MINIO_ROOT_USER", ACCESS)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentApplicationService application;
    @Autowired DocumentDeletionPurgeWorker worker;
    @Autowired DocumentDeletionJobRepository jobs;
    @Autowired MinioClient minio;

    private UUID ownerId;
    private UUID projectId;
    private UUID documentId;
    private String objectKey;

    @BeforeEach
    void seed() throws Exception {
        jdbc.execute("TRUNCATE TABLE document_deletion_job");
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        objectKey = "projects/" + projectId + "/documents/" + documentId + "/source.txt";
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, display_name)
                VALUES (?, ?, 'test-hash', 'MinIO 停机测试用户')
                """, ownerId, "minio-" + ownerId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO project(id, name, owner_id, created_by)
                VALUES (?, 'MinIO 停机测试', ?, ?)
                """, projectId, ownerId, ownerId);
        jdbc.update("""
                INSERT INTO project_member(project_id, user_id, role)
                VALUES (?, ?, 'OWNER')
                """, projectId, ownerId);
        jdbc.update("""
                INSERT INTO project_document(
                    id, project_id, display_name, original_filename, mime_type,
                    size_bytes, object_key, status, chunk_count, uploaded_by)
                VALUES (?, ?, 'MinIO停机.txt', 'MinIO停机.txt', 'text/plain',
                    12, ?, 'READY', 0, ?)
                """, documentId, projectId, objectKey, ownerId);
        if (!MINIO.isRunning()) {
            MINIO.start();
        }
        ensureBucket();
        byte[] content = "MinIO outage".getBytes(StandardCharsets.UTF_8);
        minio.putObject(PutObjectArgs.builder()
                .bucket(BUCKET).object(objectKey)
                .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                .contentType("text/plain").build());
    }

    @Test
    void stoppedMinioLeavesPendingJobAndRestartCompletesPurge() throws Exception {
        application.delete(projectId, documentId, ownerId);

        MINIO.stop();
        assertThat(worker.processDueBatch()).isZero();
        var failed = jobs.findByDocument(projectId, documentId).orElseThrow();
        assertThat(failed.status()).isEqualTo(DocumentDeletionJobStatus.PENDING);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("ERROR:DOCUMENT_STORAGE_UNAVAILABLE");
        assertThat(documentStatus()).isEqualTo("DELETING");

        MINIO.start();
        ensureBucket();
        jdbc.update("""
                UPDATE document_deletion_job SET next_attempt_at=now()
                WHERE document_id=? AND status='PENDING'
                """, documentId);
        assertThat(worker.processDueBatch()).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM document_deletion_job WHERE document_id=?",
                String.class, documentId)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE id=?",
                Integer.class, documentId)).isZero();
    }

    @Test
    void interruptionAfterObjectDeletionIsRecoveredByLeaseReconciler() throws Exception {
        application.delete(projectId, documentId, ownerId);
        minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(objectKey).build());
        jdbc.update("""
                UPDATE document_deletion_job
                SET status='RUNNING', lease_until=now()-INTERVAL '1 minute'
                WHERE document_id=?
                """, documentId);

        var reconciled = worker.reconcileNow();

        assertThat(reconciled.recoveredLeases()).isEqualTo(1);
        assertThat(worker.processDueBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM document_deletion_job WHERE document_id=?",
                String.class, documentId)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE id=?",
                Integer.class, documentId)).isZero();
    }

    private void ensureBucket() throws Exception {
        if (!minio.bucketExists(io.minio.BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    private String documentStatus() {
        return jdbc.queryForObject("SELECT status FROM project_document WHERE id=?",
                String.class, documentId);
    }
}
