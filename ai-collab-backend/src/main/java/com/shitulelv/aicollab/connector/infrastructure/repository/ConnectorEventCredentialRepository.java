package com.shitulelv.aicollab.connector.infrastructure.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public class ConnectorEventCredentialRepository {
    public record Credentials(String encryptKeyCipher, String verificationTokenCipher, boolean verified) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record GrantKey(UUID projectId, UUID authorizationId) { }
    private final JdbcTemplate jdbc;
    public ConnectorEventCredentialRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Credentials> find(UUID connectionId) {
        return jdbc.query("SELECT encrypt_key_cipher,verification_token_cipher,verified_at IS NOT NULL FROM connector_event_credential WHERE connection_id=?",
                (rs,n) -> new Credentials(rs.getString(1),rs.getString(2),rs.getBoolean(3)), connectionId).stream().findFirst();
    }

    @Transactional
    public List<GrantKey> save(UUID connectionId, String encryptCipher, String tokenCipher) {
        jdbc.queryForList("SELECT id FROM connector_connection WHERE id=? FOR UPDATE",connectionId);
        List<GrantKey> affected = jdbc.query("""
                UPDATE connector_oauth_authorization SET status='REAUTH_REQUIRED',token_version=token_version+1,
                  refresh_claim=NULL,refresh_started_at=NULL,updated_at=now()
                WHERE connection_id=? AND status<>'REVOKED' RETURNING project_id,id
                """,(rs,n) -> new GrantKey(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),connectionId);
        jdbc.update("""
                INSERT INTO connector_event_credential(connection_id,encrypt_key_cipher,verification_token_cipher)
                VALUES (?,?,?) ON CONFLICT(connection_id) DO UPDATE SET
                  encrypt_key_cipher=EXCLUDED.encrypt_key_cipher,
                  verification_token_cipher=EXCLUDED.verification_token_cipher,verified_at=NULL,updated_at=now()
                """, connectionId, encryptCipher, tokenCipher);
        return affected;
    }

    public boolean markVerified(UUID connectionId, Credentials credentials) {
        return jdbc.update("""
                UPDATE connector_event_credential SET verified_at=now(),updated_at=now()
                WHERE connection_id=? AND encrypt_key_cipher=? AND verification_token_cipher=?
                """,connectionId,credentials.encryptKeyCipher(),credentials.verificationTokenCipher()) == 1;
    }
}
