package com.shitulelv.aicollab.connector.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Authenticated event IDs and subject markers fence delayed callbacks and duplicate delivery. */
@Service
public class ConnectorEventProcessor {
    private final JdbcTemplate jdbc;
    private final ConnectorVisibilityService visibility;
    public ConnectorEventProcessor(JdbcTemplate jdbc, ConnectorVisibilityService visibility) {
        this.jdbc = jdbc;
        this.visibility = visibility;
    }

    @Transactional
    public void revokeTokens(UUID connectionId, String subjectHash, String eventId, Instant occurredAt) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", subjectHash);
        int inserted = jdbc.update("""
                INSERT INTO connector_oauth_event_receipt(connection_id,event_id,occurred_at) VALUES (?,?,?)
                ON CONFLICT(connection_id,event_id) DO NOTHING
                """, connectionId, eventId, Timestamp.from(occurredAt));
        if (inserted == 0) return;
        jdbc.update("""
                INSERT INTO connector_oauth_revocation_marker(connection_id,subject_hash,occurred_at) VALUES (?,?,?)
                ON CONFLICT(connection_id,subject_hash) DO UPDATE SET
                  occurred_at=GREATEST(connector_oauth_revocation_marker.occurred_at,EXCLUDED.occurred_at)
                """, connectionId, subjectHash, Timestamp.from(occurredAt));
        record GrantKey(UUID projectId, UUID id) { }
        var affected = jdbc.query("""
                UPDATE connector_oauth_authorization SET status='REAUTH_REQUIRED',token_version=token_version+1,
                  refresh_claim=NULL,refresh_started_at=NULL,updated_at=now()
                WHERE connection_id=? AND feishu_subject_hash=? AND token_issued_at<=? AND status<>'REVOKED'
                RETURNING project_id,id
                """, (rs,n) -> new GrantKey(rs.getObject("project_id",UUID.class),rs.getObject("id",UUID.class)),
                connectionId,subjectHash,Timestamp.from(occurredAt));
        for (var grant : affected) visibility.blockAuthorization(grant.projectId(),grant.id(),false);
    }
}
