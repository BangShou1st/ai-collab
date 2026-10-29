package com.shitulelv.aicollab.connector.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Fail-closed visibility first; physical deletion is a separate, durable operation. */
@Service
public class ConnectorVisibilityService {
    private final JdbcTemplate jdbc;

    public ConnectorVisibilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialize import commits with disconnect; revocation can then journal every committed document. */
    @Transactional
    public boolean withActiveSource(UUID projectId, UUID sourceId, Runnable action) {
        jdbc.queryForList("""
                SELECT a.id FROM connector_oauth_authorization a JOIN connector_source s
                  ON s.user_authorization_id=a.id AND s.project_id=a.project_id
                WHERE s.project_id=? AND s.id=? FOR SHARE OF a
                """, UUID.class, projectId, sourceId);
        var active = jdbc.queryForList("""
                SELECT s.id FROM connector_source s LEFT JOIN connector_oauth_authorization a
                  ON a.id=s.user_authorization_id AND a.project_id=s.project_id
                WHERE s.project_id=? AND s.id=? AND s.status='ACTIVE'
                  AND (s.user_authorization_id IS NULL OR (a.id IS NOT NULL AND a.status='ACTIVE' AND a.access_expires_at>now()))
                  AND NOT EXISTS (SELECT 1 FROM connector_deletion_journal j JOIN connector_cleanup_job q ON q.journal_id=j.id
                    WHERE j.project_id=s.project_id AND j.source_id=s.id AND q.status<>'COMPLETE')
                FOR SHARE OF s
                """, UUID.class, projectId, sourceId);
        if (active.isEmpty()) return false;
        action.run();
        return true;
    }

    @Transactional
    public void blockSource(UUID projectId, UUID sourceId, boolean confirmedDeletion) {
        int found = jdbc.update("""
                UPDATE connector_source SET status='BLOCKED',updated_at=now()
                WHERE project_id=? AND id=?
                """, projectId, sourceId);
        if (found != 1) throw new IllegalArgumentException("source not found in project");
        jdbc.update("""
                UPDATE project_document d SET access_blocked=TRUE,updated_at=now()
                FROM external_document e JOIN connector_source s ON s.id=e.source_id
                WHERE s.project_id=? AND s.id=? AND d.project_id=s.project_id AND d.id=e.document_id
                """, projectId, sourceId);
        jdbc.update("UPDATE external_document SET status='BLOCKED',updated_at=now() WHERE source_id=? AND status='ACTIVE'", sourceId);
        // Older answers do not reliably record every source used by the model.
        hideExistingAnswers(projectId, sourceId);
        if (confirmedDeletion) enqueueDeletion(projectId, sourceId);
    }

    @Transactional
    public void blockAuthorization(UUID projectId, UUID authorizationId, boolean confirmedDeletion) {
        List<UUID> sources = jdbc.queryForList("""
                SELECT id FROM connector_source WHERE project_id=? AND user_authorization_id=?
                """, UUID.class, projectId, authorizationId);
        for (UUID source : sources) blockSource(projectId, source, confirmedDeletion);
    }

    @Transactional
    public void blockDocument(UUID projectId, UUID sourceId, UUID externalDocumentId) {
        int count = jdbc.update("""
                UPDATE project_document d SET access_blocked=TRUE,updated_at=now()
                FROM external_document e JOIN connector_source s ON s.id=e.source_id
                WHERE s.project_id=? AND s.id=? AND e.id=? AND d.id=e.document_id AND d.project_id=s.project_id
                """, projectId, sourceId, externalDocumentId);
        if (count == 0) return;
        jdbc.update("UPDATE external_document SET status='BLOCKED',updated_at=now() WHERE source_id=? AND id=?",
                sourceId, externalDocumentId);
        hideExistingAnswers(projectId, sourceId);
    }

    @Transactional
    public boolean restoreSource(UUID projectId, UUID sourceId) {
        int updated = jdbc.update("""
                UPDATE connector_source s SET status='ACTIVE',last_verified_at=now(),updated_at=now()
                WHERE s.project_id=? AND s.id=?
                  AND (s.user_authorization_id IS NULL OR EXISTS (
                    SELECT 1 FROM connector_oauth_authorization a
                    WHERE a.id=s.user_authorization_id AND a.project_id=s.project_id AND a.status='ACTIVE'))
                  AND NOT EXISTS (SELECT 1 FROM connector_deletion_journal j
                    JOIN connector_cleanup_job q ON q.journal_id=j.id
                    WHERE j.project_id=s.project_id AND j.source_id=s.id AND q.status<>'COMPLETE')
                """, projectId, sourceId);
        if (updated != 1) return false;
        return true;
    }

    /** A listed document stays blocked until its content was independently read again. */
    @Transactional
    public boolean restoreDocument(UUID projectId, UUID sourceId, UUID externalDocumentId) {
        int restored = jdbc.update("""
                UPDATE project_document d SET access_blocked=FALSE,updated_at=now()
                FROM external_document e JOIN connector_source s ON s.id=e.source_id
                WHERE s.project_id=? AND s.id=? AND s.status='ACTIVE' AND e.id=?
                  AND d.id=e.document_id AND d.project_id=s.project_id
                  AND (s.user_authorization_id IS NULL OR EXISTS (
                    SELECT 1 FROM connector_oauth_authorization a WHERE a.id=s.user_authorization_id
                    AND a.project_id=s.project_id AND a.status='ACTIVE'))
                  AND NOT EXISTS (SELECT 1 FROM connector_deletion_journal j
                    WHERE j.project_id=s.project_id AND j.source_id=s.id AND j.document_id=d.id)
                """, projectId, sourceId, externalDocumentId);
        if (restored == 0) return false;
        jdbc.update("UPDATE external_document SET status='ACTIVE',updated_at=now() WHERE source_id=? AND id=?",
                sourceId, externalDocumentId);
        clearRestoredHistory(projectId, sourceId);
        return true;
    }

    private void clearRestoredHistory(UUID projectId, UUID sourceId) {
        jdbc.update("""
                UPDATE connector_hidden_answer SET cleared_at=now() WHERE project_id=? AND source_id=?
                  AND NOT EXISTS (SELECT 1 FROM connector_deletion_journal j WHERE j.project_id=? AND j.source_id=?)
                  AND NOT EXISTS (SELECT 1 FROM external_document e WHERE e.source_id=? AND e.status='BLOCKED')
                """, projectId, sourceId, projectId, sourceId, sourceId);
        jdbc.update("""
                UPDATE connector_hidden_agent_session SET cleared_at=now() WHERE project_id=? AND source_id=?
                  AND NOT EXISTS (SELECT 1 FROM connector_deletion_journal j WHERE j.project_id=? AND j.source_id=?)
                  AND NOT EXISTS (SELECT 1 FROM external_document e WHERE e.source_id=? AND e.status='BLOCKED')
                """, projectId, sourceId, projectId, sourceId, sourceId);
        jdbc.update("""
                UPDATE connector_hidden_agent_memory SET cleared_at=now() WHERE project_id=? AND source_id=?
                  AND NOT EXISTS (SELECT 1 FROM connector_deletion_journal j WHERE j.project_id=? AND j.source_id=?)
                  AND NOT EXISTS (SELECT 1 FROM external_document e WHERE e.source_id=? AND e.status='BLOCKED')
                """, projectId, sourceId, projectId, sourceId, sourceId);
    }

    private void hideExistingAnswers(UUID projectId, UUID sourceId) {
        jdbc.update("""
                INSERT INTO connector_hidden_answer(project_id,message_id,source_id)
                SELECT s.project_id,m.id,s.id FROM connector_source s
                JOIN knowledge_session k ON k.project_id=s.project_id
                JOIN knowledge_message m ON m.session_id=k.id AND m.role='ASSISTANT'
                WHERE s.project_id=? AND s.id=?
                ON CONFLICT(project_id,message_id,source_id) DO UPDATE SET cleared_at=NULL,hidden_at=now()
                """, projectId, sourceId);
        jdbc.update("""
                INSERT INTO connector_hidden_agent_session(project_id,session_id,source_id)
                SELECT s.project_id,a.id,s.id FROM connector_source s
                JOIN agent_session a ON a.project_id=s.project_id
                WHERE s.project_id=? AND s.id=?
                ON CONFLICT(project_id,session_id,source_id) DO UPDATE SET cleared_at=NULL,hidden_at=now()
                """, projectId, sourceId);
        jdbc.update("""
                INSERT INTO connector_hidden_agent_memory(project_id,memory_id,source_id)
                SELECT s.project_id,m.id,s.id FROM connector_source s
                JOIN agent_memory m ON m.project_id=s.project_id
                WHERE s.project_id=? AND s.id=?
                ON CONFLICT(project_id,memory_id,source_id) DO UPDATE SET cleared_at=NULL,hidden_at=now()
                """, projectId, sourceId);
    }

    private void enqueueDeletion(UUID projectId, UUID sourceId) {
        jdbc.update("""
                INSERT INTO connector_deletion_journal(id,project_id,source_id,document_id,object_key,reason)
                SELECT gen_random_uuid(),s.project_id,s.id,d.id,d.object_key,'CONFIRMED_REVOKE'
                FROM connector_source s JOIN external_document e ON e.source_id=s.id
                JOIN project_document d ON d.id=e.document_id AND d.project_id=s.project_id
                WHERE s.project_id=? AND s.id=?
                ON CONFLICT(project_id,source_id,document_id) DO NOTHING
                """, projectId, sourceId);
        jdbc.update("""
                INSERT INTO connector_cleanup_job(id,journal_id)
                SELECT gen_random_uuid(),j.id FROM connector_deletion_journal j
                WHERE j.project_id=? AND j.source_id=?
                ON CONFLICT(journal_id) DO NOTHING
                """, projectId, sourceId);
    }
}
