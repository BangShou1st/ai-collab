package com.shitulelv.aicollab.connector.infrastructure.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcConnectorOAuthRepository implements ConnectorOAuthRepository {
    private final JdbcTemplate jdbc;

    public JdbcConnectorOAuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Grant> find(UUID projectId, UUID authorizationId) {
        return jdbc.query("""
                SELECT id,project_id,connection_id,user_id,status,access_token_cipher,refresh_token_cipher,
                       access_expires_at,refresh_expires_at,granted_scope,token_version,refresh_claim
                FROM connector_oauth_authorization WHERE project_id=? AND id=?
                """, (rs, n) -> new Grant(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("connection_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("status"),
                rs.getString("access_token_cipher"), rs.getString("refresh_token_cipher"),
                instant(rs.getTimestamp("access_expires_at")), instant(rs.getTimestamp("refresh_expires_at")),
                rs.getString("granted_scope"), rs.getLong("token_version"), rs.getObject("refresh_claim", UUID.class)),
                projectId, authorizationId).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Grant> claimRefresh(UUID projectId, UUID authorizationId, long version, UUID claimId) {
        int count = jdbc.update("""
                UPDATE connector_oauth_authorization SET status='REFRESHING',refresh_claim=?,
                  refresh_started_at=now(),token_version=token_version+1,updated_at=now()
                WHERE project_id=? AND id=? AND status='ACTIVE' AND token_version=?
                """, claimId, projectId, authorizationId, version);
        return count == 1 ? find(projectId, authorizationId) : Optional.empty();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeRefresh(UUID projectId, UUID authorizationId, long version, UUID claimId,
                                   String accessCipher, String refreshCipher, Instant accessExpiresAt,
                                   Instant refreshExpiresAt, String scope, String status) {
        if (!status.equals("ACTIVE") && !status.equals("REAUTH_REQUIRED")) {
            throw new IllegalArgumentException("invalid refresh completion status");
        }
        return jdbc.update("""
                UPDATE connector_oauth_authorization SET status=?,access_token_cipher=?,refresh_token_cipher=?,
                  access_expires_at=?,refresh_expires_at=?,granted_scope=CASE WHEN ?='' THEN granted_scope ELSE ? END,
                  refresh_claim=NULL,refresh_started_at=NULL,updated_at=now()
                WHERE project_id=? AND id=? AND status='REFRESHING' AND token_version=? AND refresh_claim=?
                """, status, accessCipher, refreshCipher, Timestamp.from(accessExpiresAt), Timestamp.from(refreshExpiresAt),
                scope, scope, projectId, authorizationId, version, claimId) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failRefresh(UUID projectId, UUID authorizationId, long version, UUID claimId, String status) {
        if (!status.equals("ACTIVE") && !status.equals("REAUTH_REQUIRED") && !status.equals("REFRESH_UNCERTAIN")) {
            throw new IllegalArgumentException("invalid refresh outcome");
        }
        return jdbc.update("""
                UPDATE connector_oauth_authorization SET status=?,refresh_claim=NULL,refresh_started_at=NULL,updated_at=now()
                WHERE project_id=? AND id=? AND status='REFRESHING' AND token_version=? AND refresh_claim=?
                """, status, projectId, authorizationId, version, claimId) == 1;
    }

    @Override
    public void saveFlow(String stateHash, UUID projectId, UUID connectionId, UUID userId,
                         String verifierCipher, String redirectUri, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO connector_oauth_flow(state_hash,project_id,connection_id,user_id,verifier_cipher,redirect_uri,expires_at,grant_version)
                VALUES (?,?,?,?,?,?,?,(SELECT token_version FROM connector_oauth_authorization
                  WHERE project_id=? AND connection_id=? AND user_id=?))
                """, stateHash, projectId, connectionId, userId, verifierCipher, redirectUri, Timestamp.from(expiresAt),
                projectId, connectionId, userId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Flow> consumeFlow(String stateHash) {
        return jdbc.query("""
                UPDATE connector_oauth_flow SET consumed_at=now()
                WHERE state_hash=? AND consumed_at IS NULL AND expires_at>now()
                RETURNING project_id,connection_id,user_id,verifier_cipher,redirect_uri,grant_version
                """, (rs, n) -> new Flow(rs.getObject("project_id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("verifier_cipher"), rs.getString("redirect_uri"),
                rs.getObject("grant_version", Long.class)),
                stateHash).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> saveAuthorization(UUID projectId, UUID connectionId, UUID userId, String accessCipher,
                                  String refreshCipher, Instant accessExpiresAt, Instant refreshExpiresAt, String scope,
                                  Long expectedVersion, String subjectHash, Instant tokenIssuedAt) {
        if (subjectHash == null || !subjectHash.matches("[0-9a-f]{64}") || tokenIssuedAt == null) return Optional.empty();
        jdbc.queryForList("SELECT id FROM connector_connection WHERE id=? FOR UPDATE",connectionId);
        Boolean eventReady = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM connector_event_credential WHERE connection_id=? AND verified_at IS NOT NULL)",Boolean.class,connectionId);
        if (!Boolean.TRUE.equals(eventReady)) return Optional.empty();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", subjectHash);
        Boolean notRevoked = jdbc.queryForObject("""
                SELECT NOT EXISTS (SELECT 1 FROM connector_oauth_revocation_marker
                  WHERE connection_id=? AND subject_hash=? AND occurred_at>=?)
                """, Boolean.class, connectionId, subjectHash, Timestamp.from(tokenIssuedAt));
        if (!Boolean.TRUE.equals(notRevoked)) return Optional.empty();
        if (expectedVersion == null) return jdbc.query("""
                INSERT INTO connector_oauth_authorization(id,project_id,connection_id,user_id,status,access_token_cipher,
                  refresh_token_cipher,access_expires_at,refresh_expires_at,granted_scope,feishu_subject_hash,token_issued_at)
                VALUES (?,?,?,?,'ACTIVE',?,?,?,?,?,?,?)
                ON CONFLICT(project_id,connection_id,user_id) DO NOTHING
                RETURNING id
                """, (rs,n) -> rs.getObject(1,UUID.class), UUID.randomUUID(), projectId, connectionId, userId,
                accessCipher, refreshCipher, Timestamp.from(accessExpiresAt), Timestamp.from(refreshExpiresAt), scope,
                subjectHash, Timestamp.from(tokenIssuedAt))
                .stream().findFirst();
        return jdbc.query("""
                UPDATE connector_oauth_authorization SET status='ACTIVE',access_token_cipher=?,refresh_token_cipher=?,
                  access_expires_at=?,refresh_expires_at=?,granted_scope=?,token_version=token_version+1,
                  feishu_subject_hash=?,token_issued_at=?,refresh_claim=NULL,refresh_started_at=NULL,updated_at=now()
                WHERE project_id=? AND connection_id=? AND user_id=? AND token_version=? RETURNING id
                """, (rs,n) -> rs.getObject(1,UUID.class), accessCipher, refreshCipher,
                Timestamp.from(accessExpiresAt), Timestamp.from(refreshExpiresAt), scope, subjectHash, Timestamp.from(tokenIssuedAt),
                projectId, connectionId, userId, expectedVersion).stream().findFirst();
    }

    @Override
    public List<Grant> listOwn(UUID projectId, UUID userId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM connector_oauth_authorization WHERE project_id=? AND user_id=? ORDER BY created_at
                """, UUID.class, projectId, userId);
        return ids.stream().map(id -> find(projectId, id)).flatMap(Optional::stream).toList();
    }

    @Override
    public boolean revoke(UUID projectId, UUID authorizationId) {
        return jdbc.update("""
                UPDATE connector_oauth_authorization SET status='REVOKED',access_token_cipher=NULL,
                  refresh_token_cipher=NULL,token_version=token_version+1,refresh_claim=NULL,
                  refresh_started_at=NULL,updated_at=now()
                WHERE project_id=? AND id=? AND status<>'REVOKED'
                """, projectId, authorizationId) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Grant> markAbandonedRefreshes() {
        record Key(UUID projectId, UUID id) {}
        List<Key> keys = jdbc.query("""
                UPDATE connector_oauth_authorization SET status='REFRESH_UNCERTAIN',refresh_claim=NULL,
                  refresh_started_at=NULL,updated_at=now()
                WHERE status='REFRESHING' AND refresh_started_at < now() - INTERVAL '2 minutes'
                RETURNING project_id,id
                """, (rs, n) -> new Key(rs.getObject("project_id", UUID.class), rs.getObject("id", UUID.class)));
        return keys.stream().map(k -> find(k.projectId(), k.id())).flatMap(Optional::stream).toList();
    }

    @Override
    public List<GrantKey> dueRefreshes() {
        return jdbc.query("""
                SELECT project_id,id FROM connector_oauth_authorization
                WHERE status='ACTIVE' AND access_expires_at<=now()+INTERVAL '5 minutes'
                ORDER BY access_expires_at,id LIMIT 100
                """, (rs, n) -> new GrantKey(rs.getObject("project_id", UUID.class), rs.getObject("id", UUID.class)));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
