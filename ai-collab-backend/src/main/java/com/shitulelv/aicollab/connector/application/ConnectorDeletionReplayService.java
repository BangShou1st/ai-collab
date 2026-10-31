package com.shitulelv.aicollab.connector.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Offline recovery building block. Its caller must verify the independent journal before replay. */
@Service
public class ConnectorDeletionReplayService {
    public record Tombstone(UUID projectId, UUID sourceId, UUID documentId, String objectKey) { }
    private final JdbcTemplate jdbc;
    private final ConnectorVisibilityService visibility;
    private final boolean enabled;

    public ConnectorDeletionReplayService(JdbcTemplate jdbc, ConnectorVisibilityService visibility,
            @Value("${connector.recovery.replay-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.visibility = visibility;
        this.enabled = enabled;
    }

    @Transactional
    public void replay(List<Tombstone> records) {
        if (!enabled || records == null || records.size() > 1000) throw new IllegalStateException("恢复重放未启用或批次过大");
        for (Tombstone record : records) {
            if (record == null || record.projectId() == null || record.sourceId() == null || record.documentId() == null
                    || record.objectKey() == null || !record.objectKey().startsWith(
                    "projects/" + record.projectId() + "/documents/" + record.documentId() + "/")) {
                throw new IllegalArgumentException("删除记录身份无效");
            }
            var existing = jdbc.queryForList("SELECT object_key FROM project_document WHERE project_id=? AND id=?",
                    String.class, record.projectId(), record.documentId());
            if (!existing.isEmpty() && !existing.getFirst().equals(record.objectKey())) {
                throw new IllegalArgumentException("恢复对象身份不匹配");
            }
            jdbc.update("UPDATE project_document SET access_blocked=TRUE,updated_at=now() WHERE project_id=? AND id=?",
                    record.projectId(), record.documentId());
            Integer sourceCount = jdbc.queryForObject("SELECT count(*) FROM connector_source WHERE project_id=? AND id=?",
                    Integer.class, record.projectId(), record.sourceId());
            if (sourceCount != null && sourceCount == 1) visibility.blockSource(record.projectId(), record.sourceId(), false);
            // A backup can predate the source row itself; old answers remain quarantined in that case too.
            jdbc.update("""
                    INSERT INTO connector_hidden_answer(project_id,message_id,source_id)
                    SELECT k.project_id,m.id,? FROM knowledge_session k JOIN knowledge_message m ON m.session_id=k.id
                    WHERE k.project_id=? AND m.role='ASSISTANT'
                    ON CONFLICT(project_id,message_id,source_id) DO UPDATE SET cleared_at=NULL,hidden_at=now()
                    """, record.sourceId(), record.projectId());
            jdbc.update("""
                    INSERT INTO connector_hidden_agent_session(project_id,session_id,source_id)
                    SELECT project_id,id,? FROM agent_session WHERE project_id=?
                    ON CONFLICT(project_id,session_id,source_id) DO UPDATE SET cleared_at=NULL,hidden_at=now()
                    """, record.sourceId(), record.projectId());
            jdbc.update("""
                    INSERT INTO connector_hidden_agent_memory(project_id,memory_id,source_id)
                    SELECT project_id,id,? FROM agent_memory WHERE project_id=?
                    ON CONFLICT(project_id,memory_id,source_id) DO UPDATE SET cleared_at=NULL,hidden_at=now()
                    """, record.sourceId(), record.projectId());
            jdbc.update("""
                    INSERT INTO connector_deletion_journal(id,project_id,source_id,document_id,object_key,reason)
                    VALUES (?,?,?,?,?,'RECOVERY_REPLAY') ON CONFLICT(project_id,source_id,document_id) DO NOTHING
                    """, UUID.randomUUID(), record.projectId(), record.sourceId(), record.documentId(), record.objectKey());
            var journal = jdbc.queryForList("""
                    SELECT id FROM connector_deletion_journal WHERE project_id=? AND source_id=? AND document_id=? AND object_key=?
                    """, UUID.class, record.projectId(), record.sourceId(), record.documentId(), record.objectKey());
            if (journal.size() != 1) throw new IllegalArgumentException("恢复删除记录冲突");
            jdbc.update("""
                    INSERT INTO connector_cleanup_job(id,journal_id) VALUES (?,?)
                    ON CONFLICT(journal_id) DO UPDATE SET status='PENDING',next_attempt_at=now(),claim_id=NULL,
                      lease_until=NULL,last_error_code=NULL,updated_at=now()
                    """, UUID.randomUUID(), journal.getFirst());
        }
    }
}
