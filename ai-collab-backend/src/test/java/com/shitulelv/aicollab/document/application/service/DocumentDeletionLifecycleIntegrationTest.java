package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentDeletionJobStatus;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentDeletionJob;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentDeletionJobRepository;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "agent.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket",
        "document.dispatch-interval=3600s",
        "document.dispatch-initial-delay=3600s",
        "document.deletion.scheduler-enabled=false",
        "document.deletion.max-attempts=5"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class DocumentDeletionLifecycleIntegrationTest {
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

    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentApplicationService application;
    @Autowired DocumentDeletionPurgeWorker worker;
    @Autowired DocumentDeletionJobRepository deletionJobs;
    @Autowired DocumentRepository documents;
    @MockitoBean DocumentStorageGateway storage;

    private UUID ownerId;
    private UUID projectId;
    private UUID documentId;
    private String objectKey;

    @BeforeEach
    void seed() {
        unblockDocumentDelete();
        unblockJobInsert();
        jdbc.execute("TRUNCATE TABLE document_deletion_job");
        jdbc.execute("TRUNCATE TABLE app_user CASCADE");
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        objectKey = "projects/" + projectId + "/documents/" + documentId + "/source.txt";
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, display_name)
                VALUES (?, ?, 'test-hash', '删除测试用户')
                """, ownerId, "delete-" + ownerId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO project(id, name, owner_id, created_by)
                VALUES (?, '删除任务测试', ?, ?)
                """, projectId, ownerId, ownerId);
        jdbc.update("""
                INSERT INTO project_member(project_id, user_id, role)
                VALUES (?, ?, 'OWNER')
                """, projectId, ownerId);
        jdbc.update("""
                INSERT INTO project_document(
                    id, project_id, display_name, original_filename, mime_type,
                    size_bytes, object_key, status, chunk_count, uploaded_by)
                VALUES (?, ?, '删除测试.txt', '删除测试.txt', 'text/plain',
                    12, ?, 'READY', 1, ?)
                """, documentId, projectId, objectKey, ownerId);
        jdbc.update("""
                INSERT INTO document_chunk(
                    id, project_id, document_id, chunk_no, content, content_hash,
                    token_estimate, embedding_provider, embedding_model, embedding_dimension,
                    embedding_fingerprint, embedding)
                VALUES (?, ?, ?, 0, '待删除正文', ?, 4, 'test-provider', 'test-model', 1, ?, '[0.1]'::vector)
                """, UUID.randomUUID(), projectId, documentId,
                "a".repeat(64), "b".repeat(64));
    }

    @Test
    void duplicateDeleteKeepsOneJobAndPurgeRemovesObjectRowsAndAuditAtomically() {
        var first = application.delete(projectId, documentId, ownerId);
        var second = application.delete(projectId, documentId, ownerId);

        assertThat(second.deletionId()).isEqualTo(first.deletionId());
        assertThat(first.status()).isEqualTo(DocumentDeletionJobStatus.PENDING);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_deletion_job WHERE document_id=?",
                Integer.class, documentId)).isEqualTo(1);
        assertThat(status()).isEqualTo("DELETING");
        verify(storage, never()).delete(anyString());

        assertThat(worker.processDueBatch()).isEqualTo(1);

        verify(storage, times(1)).delete(objectKey);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE id=?", Integer.class, documentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id=?", Integer.class, documentId)).isZero();
        assertThat(jobStatus()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE project_id=? AND entity_id=? AND action='DOCUMENT_DELETED'",
                Integer.class, projectId, documentId)).isEqualTo(1);
    }

    @Test
    void storageOutageLeavesDurablePendingJobAndRecoversAfterRetry() {
        doThrow(new BusinessException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE))
                .when(storage).delete(objectKey);
        application.delete(projectId, documentId, ownerId);

        assertThat(worker.processDueBatch()).isZero();

        DocumentDeletionJob failed = job();
        assertThat(failed.status()).isEqualTo(DocumentDeletionJobStatus.PENDING);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("ERROR:DOCUMENT_STORAGE_UNAVAILABLE");
        assertThat(status()).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id=?", Integer.class, documentId)).isEqualTo(1);

        doNothing().when(storage).delete(objectKey);
        makeDue();
        assertThat(worker.processDueBatch()).isEqualTo(1);

        assertThat(jobStatus()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE id=?", Integer.class, documentId)).isZero();
    }

    @Test
    void databaseFailureAfterObjectDeletionRetriesWithoutOrphanDeletingRow() {
        blockDocumentDelete();
        application.delete(projectId, documentId, ownerId);

        assertThat(worker.processDueBatch()).isZero();

        verify(storage, times(1)).delete(objectKey);
        assertThat(status()).isEqualTo("DELETING");
        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(job().attempts()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE id=?", Integer.class, documentId)).isEqualTo(1);

        unblockDocumentDelete();
        makeDue();
        assertThat(worker.processDueBatch()).isEqualTo(1);

        assertThat(jobStatus()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE id=?", Integer.class, documentId)).isZero();
    }

    @Test
    void fiveFailuresBecomeFailedAndSameDeleteRequeuesForSuccess() {
        doThrow(new IllegalStateException("STORAGE_OFFLINE")).when(storage).delete(objectKey);
        application.delete(projectId, documentId, ownerId);
        DocumentDeletionJob current = job();

        for (int attempt = 1; attempt <= 5; attempt++) {
            makeDue();
            worker.processDueBatch();
            current = job();
            assertThat(current.attempts()).isEqualTo(attempt);
            assertThat(current.status()).isEqualTo(attempt < 5
                    ? DocumentDeletionJobStatus.PENDING
                    : DocumentDeletionJobStatus.FAILED);
        }
        assertThat(current.lastError()).isEqualTo("ERROR:STORAGE_OFFLINE");

        doNothing().when(storage).delete(objectKey);
        var requeued = application.delete(projectId, documentId, ownerId);
        assertThat(requeued.deletionId()).isEqualTo(current.id());
        assertThat(requeued.status()).isEqualTo(DocumentDeletionJobStatus.PENDING);
        assertThat(job().attempts()).isZero();

        makeDue();
        assertThat(worker.processDueBatch()).isEqualTo(1);
        assertThat(jobStatus()).isEqualTo("DONE");
    }

    @Test
    void reconcilerCreatesMissingJobAndRecoversExpiredLeaseThenTreatsMissingObjectAsDeleted() {
        jdbc.update("UPDATE project_document SET status='DELETING' WHERE id=?", documentId);

        var reconciled = worker.reconcileNow();
        assertThat(reconciled.orphanedDocuments()).isEqualTo(1);
        assertThat(jobStatus()).isEqualTo("PENDING");

        jdbc.update("""
                UPDATE document_deletion_job
                SET status='RUNNING', lease_until=now()-INTERVAL '1 minute'
                WHERE document_id=?
                """, documentId);
        var recovered = worker.reconcileNow();
        assertThat(recovered.recoveredLeases()).isEqualTo(1);
        assertThat(jobStatus()).isEqualTo("PENDING");

        doThrow(new BusinessException(ErrorCode.DOCUMENT_STORAGE_OBJECT_NOT_FOUND))
                .when(storage).delete(objectKey);
        assertThat(worker.processDueBatch()).isEqualTo(1);

        assertThat(jobStatus()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_document WHERE id=?", Integer.class, documentId)).isZero();
    }

    @Test
    void jobInsertFailureRollsBackDeletingMarkerInSameTransaction() {
        blockJobInsert();
        try {
            assertThatThrownBy(() -> application.delete(projectId, documentId, ownerId))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            unblockJobInsert();
        }

        assertThat(status()).isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_deletion_job WHERE document_id=?",
                Integer.class, documentId)).isZero();
    }

    @Test
    void objectKeyOutsideDocumentPrefixIsFailedWithoutCallingStorage() {
        application.delete(projectId, documentId, ownerId);
        jdbc.update("UPDATE document_deletion_job SET object_key=? WHERE document_id=?",
                "projects/other/documents/other/source.txt", documentId);

        assertThat(worker.processDueBatch()).isZero();

        verify(storage, never()).delete(anyString());
        assertThat(job().lastError()).isEqualTo("ERROR:OBJECT_KEY_MISMATCH");
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM project_document WHERE id=?", String.class, documentId);
    }

    private String jobStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM document_deletion_job WHERE document_id=?", String.class, documentId);
    }

    private DocumentDeletionJob job() {
        return deletionJobs.findByDocument(projectId, documentId).orElseThrow();
    }

    private void makeDue() {
        jdbc.update("""
                UPDATE document_deletion_job SET next_attempt_at=now()
                WHERE document_id=? AND status='PENDING'
                """, documentId);
    }

    private void blockDocumentDelete() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_document_delete_for_test() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'injected database delete failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_document_delete_for_test
                BEFORE DELETE ON project_document
                FOR EACH ROW EXECUTE FUNCTION reject_document_delete_for_test()
                """);
    }

    private void unblockDocumentDelete() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_document_delete_for_test ON project_document");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_document_delete_for_test()");
    }

    private void blockJobInsert() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_document_deletion_job_insert_for_test() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'injected job insert failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_document_deletion_job_insert_for_test
                BEFORE INSERT ON document_deletion_job
                FOR EACH ROW EXECUTE FUNCTION reject_document_deletion_job_insert_for_test()
                """);
    }

    private void unblockJobInsert() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_document_deletion_job_insert_for_test ON document_deletion_job");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_document_deletion_job_insert_for_test()");
    }
}
