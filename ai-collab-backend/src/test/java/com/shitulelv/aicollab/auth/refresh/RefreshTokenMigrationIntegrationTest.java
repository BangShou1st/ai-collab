package com.shitulelv.aicollab.auth.refresh;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
class RefreshTokenMigrationIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String schema;

    @AfterEach
    void removeMigrationSchema() {
        if (schema != null) {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void extendsLegacyRefreshTokensWithSessionSafetyConstraintsAndIndexes() {
        schema = "refresh_token_migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);

        migrateTo("1");

        UUID userId = UUID.randomUUID();
        UUID legacyTokenId = UUID.randomUUID();
        OffsetDateTime legacyExpiresAt = OffsetDateTime.of(2026, 8, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO %s.app_user (id, username, password_hash, display_name)
                        VALUES (?, ?, ?, ?)
                        """.formatted(schema),
                userId, "legacy_" + userId.toString().substring(0, 8), "not-a-real-password", "Legacy User");
        jdbcTemplate.update("""
                        INSERT INTO %s.refresh_token (id, user_id, token_hash, expires_at)
                        VALUES (?, ?, ?, ?)
                        """.formatted(schema),
                legacyTokenId, userId, "a".repeat(64), legacyExpiresAt);

        migrateToLatest();

        Map<String, String> nullabilityByColumn = jdbcTemplate.query("""
                        SELECT column_name, is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = ?
                          AND table_name = 'refresh_token'
                          AND column_name IN ('session_id', 'session_expires_at', 'revoke_reason', 'replaced_by_token_id')
                        """,
                resultSet -> {
                    Map<String, String> columns = new java.util.HashMap<>();
                    while (resultSet.next()) {
                        columns.put(resultSet.getString("column_name"), resultSet.getString("is_nullable"));
                    }
                    return columns;
                }, schema);

        assertThat(nullabilityByColumn)
                .containsEntry("session_id", "NO")
                .containsEntry("session_expires_at", "NO")
                .containsEntry("revoke_reason", "YES")
                .containsEntry("replaced_by_token_id", "YES");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT session_id IS NOT NULL AND session_expires_at = expires_at
                        FROM %s.refresh_token
                        WHERE id = ?
                        """.formatted(schema), Boolean.class, legacyTokenId))
                .isTrue();

        String revokeReasonConstraint = jdbcTemplate.queryForObject("""
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conrelid = ?::regclass
                          AND contype = 'c'
                          AND pg_get_constraintdef(oid) LIKE '%%revoke_reason%%'
                        """, String.class, schema + ".refresh_token");
        assertThat(revokeReasonConstraint)
                .contains("ROTATED", "LOGOUT", "REUSE_DETECTED", "EXPIRED", "USER_UNAVAILABLE");

        Map<String, Object> replacementForeignKey = jdbcTemplate.queryForMap("""
                        SELECT conname, confdeltype
                        FROM pg_constraint
                        WHERE conrelid = ?::regclass
                          AND contype = 'f'
                          AND pg_get_constraintdef(oid) LIKE '%%replaced_by_token_id%%'
                        """, schema + ".refresh_token");
        assertThat(replacementForeignKey.get("confdeltype")).isEqualTo("n");

        UUID legacySessionId = jdbcTemplate.queryForObject("""
                        SELECT session_id
                        FROM %s.refresh_token
                        WHERE id = ?
                        """.formatted(schema), UUID.class, legacyTokenId);
        UUID replacementTokenId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO %s.refresh_token (
                            id, user_id, token_hash, expires_at, session_id, session_expires_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.formatted(schema),
                replacementTokenId, userId, "b".repeat(64), legacyExpiresAt,
                legacySessionId, legacyExpiresAt);
        jdbcTemplate.update("""
                        UPDATE %s.refresh_token
                        SET replaced_by_token_id = ?
                        WHERE id = ?
                        """.formatted(schema), replacementTokenId, legacyTokenId);
        jdbcTemplate.update("""
                        DELETE FROM %s.refresh_token
                        WHERE id = ?
                        """.formatted(schema), replacementTokenId);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT replaced_by_token_id IS NULL
                        FROM %s.refresh_token
                        WHERE id = ?
                        """.formatted(schema), Boolean.class, legacyTokenId))
                .isTrue();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO %s.refresh_token (
                            id, user_id, token_hash, expires_at, session_id, session_expires_at, revoke_reason
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.formatted(schema),
                UUID.randomUUID(), userId, "c".repeat(64), legacyExpiresAt,
                legacySessionId, legacyExpiresAt, "INVALID_REASON"))
                .isInstanceOf(DataIntegrityViolationException.class);

        List<String> indexDefinitions = jdbcTemplate.queryForList("""
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = ?
                          AND tablename = 'refresh_token'
                        """, String.class, schema);
        assertThat(indexDefinitions).anyMatch(definition -> definition.contains("(session_id)"));
        assertThat(indexDefinitions).anyMatch(definition -> definition.contains("(session_id, revoked_at)"));
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private void migrateToLatest() {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
