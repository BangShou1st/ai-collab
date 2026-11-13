package com.shitulelv.aicollab.document.infrastructure.repository;

import com.shitulelv.aicollab.document.domain.model.DocumentDeletionJobStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentDeletionJobRepository {
    private static final String COLUMNS = """
            id, project_id, document_id, object_key, status, attempts,
            next_attempt_at, lease_until, last_error, created_at, updated_at
            """;
    private static final RowMapper<DocumentDeletionJob> ROW_MAPPER = (rs, rowNum) ->
            new DocumentDeletionJob(
                    rs.getObject("id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("document_id", UUID.class),
                    rs.getString("object_key"),
                    DocumentDeletionJobStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getObject("next_attempt_at", OffsetDateTime.class),
                    rs.getObject("lease_until", OffsetDateTime.class),
                    rs.getString("last_error"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public DocumentDeletionJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Must join the caller's transaction so the document DELETING marker and job are atomic.
     * A FAILED job is re-armed in place; duplicate DELETE requests keep the same deletion id.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentDeletionJob enqueueOrRetry(UUID projectId, UUID documentId, String objectKey) {
        return jdbc.queryForObject("""
                INSERT INTO document_deletion_job(id, project_id, document_id, object_key, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                ON CONFLICT (document_id) DO UPDATE SET
                    object_key=EXCLUDED.object_key,
                    status=CASE WHEN document_deletion_job.status='FAILED'
                                THEN 'PENDING' ELSE document_deletion_job.status END,
                    attempts=CASE WHEN document_deletion_job.status='FAILED'
                                  THEN 0 ELSE document_deletion_job.attempts END,
                    next_attempt_at=CASE WHEN document_deletion_job.status='FAILED'
                                         THEN now() ELSE document_deletion_job.next_attempt_at END,
                    lease_until=CASE WHEN document_deletion_job.status='FAILED'
                                     THEN NULL ELSE document_deletion_job.lease_until END,
                    last_error=CASE WHEN document_deletion_job.status='FAILED'
                                    THEN NULL ELSE document_deletion_job.last_error END,
                    updated_at=now()
                RETURNING
                """ + COLUMNS, ROW_MAPPER, UUID.randomUUID(), projectId, documentId, objectKey);
    }

    public Optional<DocumentDeletionJob> findByDocument(UUID projectId, UUID documentId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM document_deletion_job WHERE project_id=? AND document_id=?",
                ROW_MAPPER, projectId, documentId).stream().findFirst();
    }

    public Optional<DocumentDeletionJob> findById(UUID jobId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM document_deletion_job WHERE id=?",
                ROW_MAPPER, jobId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<DocumentDeletionJob> claimBatch(int limit, int leaseSeconds) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id
                    FROM document_deletion_job
                    WHERE status='PENDING' AND next_attempt_at<=now()
                    ORDER BY next_attempt_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE document_deletion_job job
                SET status='RUNNING', lease_until=now()+(? * INTERVAL '1 second'), updated_at=now()
                FROM due
                WHERE job.id=due.id
                RETURNING
                """ + prefixedColumns("job"), ROW_MAPPER, limit, leaseSeconds);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markDone(UUID jobId) {
        return jdbc.update("""
                UPDATE document_deletion_job
                SET status='DONE', lease_until=NULL, last_error=NULL, updated_at=now()
                WHERE id=? AND status='RUNNING'
                """, jobId) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markFailure(UUID jobId, int attempts, DocumentDeletionJobStatus status,
                               int delaySeconds, String lastError) {
        return jdbc.update("""
                UPDATE document_deletion_job
                SET status=?, attempts=?, next_attempt_at=now()+(? * INTERVAL '1 second'),
                    lease_until=NULL, last_error=?, updated_at=now()
                WHERE id=? AND status='RUNNING'
                """, status.name(), attempts, delaySeconds, lastError, jobId) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int reconcileOrphans() {
        return jdbc.update("""
                INSERT INTO document_deletion_job(id, project_id, document_id, object_key, status)
                SELECT gen_random_uuid(), d.project_id, d.id, d.object_key, 'PENDING'
                FROM project_document d
                WHERE d.status='DELETING'
                  AND NOT EXISTS (
                      SELECT 1 FROM document_deletion_job j
                      WHERE j.document_id=d.id
                  )
                ON CONFLICT (document_id) DO NOTHING
                """);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int recoverExpiredLeases() {
        return jdbc.update("""
                UPDATE document_deletion_job
                SET status='PENDING', lease_until=NULL, next_attempt_at=now(), updated_at=now()
                WHERE status='RUNNING' AND lease_until < now()
                """);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteCompletedBefore(OffsetDateTime cutoff) {
        return jdbc.update("DELETE FROM document_deletion_job WHERE status='DONE' AND updated_at<?", cutoff);
    }

    private static String prefixedColumns(String alias) {
        return """
                %1$s.id, %1$s.project_id, %1$s.document_id, %1$s.object_key, %1$s.status,
                %1$s.attempts, %1$s.next_attempt_at, %1$s.lease_until, %1$s.last_error,
                %1$s.created_at, %1$s.updated_at
                """.formatted(alias);
    }
}
