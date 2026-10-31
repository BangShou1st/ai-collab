package com.shitulelv.aicollab.connector.infrastructure.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ConnectorCleanupRepository {
    private final JdbcTemplate jdbc;

    public ConnectorCleanupRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Claim(UUID jobId, UUID claimId, UUID projectId, UUID sourceId,
                        UUID documentId, String objectKey, int attempts) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claim() {
        UUID lease = UUID.randomUUID();
        return jdbc.query("""
                WITH candidate AS (
                  SELECT id FROM connector_cleanup_job
                  WHERE (status='PENDING' AND next_attempt_at<=now()) OR (status='RUNNING' AND lease_until<now())
                  ORDER BY updated_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                ), claimed AS (
                  UPDATE connector_cleanup_job q SET status='RUNNING',claim_id=?,lease_until=now()+INTERVAL '5 minutes',
                    attempts=attempts+1,updated_at=now()
                  FROM candidate c WHERE q.id=c.id
                  RETURNING q.id,q.journal_id,q.attempts
                )
                SELECT c.id,j.project_id,j.source_id,j.document_id,j.object_key,c.attempts
                FROM claimed c JOIN connector_deletion_journal j ON j.id=c.journal_id
                """, (rs, n) -> new Claim(rs.getObject("id", UUID.class), lease,
                rs.getObject("project_id", UUID.class), rs.getObject("source_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("object_key"), rs.getInt("attempts")), lease)
                .stream().findFirst();
    }

    public boolean safeTarget(Claim claim) {
        if (!claim.objectKey().startsWith("projects/" + claim.projectId() + "/documents/" + claim.documentId() + "/")) return false;
        Boolean safe = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM connector_deletion_journal j
                  LEFT JOIN project_document d ON d.id=j.document_id AND d.project_id=j.project_id
                  WHERE j.project_id=? AND j.source_id=? AND j.document_id=? AND j.object_key=?
                    AND (d.id IS NULL OR (d.object_key=j.object_key AND d.access_blocked)))
                """, Boolean.class, claim.projectId(), claim.sourceId(), claim.documentId(), claim.objectKey());
        return Boolean.TRUE.equals(safe);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(Claim claim) {
        var current = jdbc.queryForList("SELECT id FROM connector_cleanup_job WHERE id=? AND status='RUNNING' AND claim_id=? FOR UPDATE",
                UUID.class, claim.jobId(), claim.claimId());
        if (current.isEmpty() || !safeTarget(claim)) return false;
        // Keep the audit and notification rows, but discard document-specific prose after confirmed deletion.
        jdbc.update("UPDATE audit_log SET detail='{}'::jsonb WHERE project_id=? AND entity_type='PROJECT_DOCUMENT' AND entity_id=?",
                claim.projectId(), claim.documentId());
        jdbc.update("UPDATE notification SET title='文档通知',content='关联文档已不可访问' WHERE project_id=? AND entity_type='PROJECT_DOCUMENT' AND entity_id=?",
                claim.projectId(), claim.documentId());
        jdbc.update("""
                DELETE FROM project_document WHERE project_id=? AND id=? AND object_key=? AND access_blocked
                """, claim.projectId(), claim.documentId(), claim.objectKey());
        jdbc.update("""
                UPDATE external_document_revision r SET content_hash=NULL,latest_modify_time=NULL
                WHERE EXISTS (SELECT 1 FROM external_document e WHERE e.id=r.external_document_id
                  AND e.source_id=? AND e.document_id=?)
                """, claim.sourceId(), claim.documentId());
        jdbc.update("""
                UPDATE external_document SET status='REMOVED',updated_at=now()
                  ,external_key='revoked:' || id::text,title=NULL,source_url=NULL,
                  latest_modify_time=NULL,content_hash=NULL
                WHERE source_id=? AND document_id=?
                """, claim.sourceId(), claim.documentId());
        jdbc.update("""
                UPDATE connector_cleanup_job SET status='COMPLETE',claim_id=NULL,lease_until=NULL,
                  last_error_code=NULL,updated_at=now() WHERE id=? AND claim_id=?
                """, claim.jobId(), claim.claimId());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(Claim claim, String errorCode) {
        int delaySeconds = Math.min(3600, 5 * (1 << Math.min(claim.attempts(), 9)));
        jdbc.update("""
                UPDATE connector_cleanup_job SET status='PENDING',claim_id=NULL,lease_until=NULL,
                  next_attempt_at=now()+(? * INTERVAL '1 second'),last_error_code=?,updated_at=now()
                WHERE id=? AND status='RUNNING' AND claim_id=?
                """, delaySeconds, errorCode, claim.jobId(), claim.claimId());
    }
}
