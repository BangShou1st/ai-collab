package com.shitulelv.aicollab.project;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
class ProjectOwnerMigrationIntegrationTest {

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
    void v3AddsOnlyPartialUniqueOwnerIndexAndKeepsInvitationHashColumnCompatible() {
        schema = "project_owner_migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);

        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        String indexDefinition = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = ?
                  AND tablename = 'project_member'
                  AND indexname = 'uq_project_member_single_owner'
                """, String.class, schema);
        assertThat(indexDefinition)
                .contains("UNIQUE")
                .contains("(project_id)")
                .contains("WHERE ((role)::text = 'OWNER'::text)");

        var invitationColumn = jdbcTemplate.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'project_invitation'
                  AND column_name = 'invite_code_hash'
                """, schema);
        assertThat(invitationColumn)
                .containsEntry("data_type", "character")
                .containsEntry("character_maximum_length", 64)
                .containsEntry("is_nullable", "NO");

        UUID owner = insertUser("owner");
        UUID secondOwner = insertUser("second");
        UUID project = insertProject(owner);
        insertMember(project, owner, "OWNER");
        assertThatThrownBy(() -> insertMember(project, secondOwner, "OWNER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertMember(project, secondOwner, "ADMIN");
    }

    private UUID insertUser(String prefix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO %s.app_user (id, username, password_hash, display_name)
                VALUES (?, ?, ?, ?)
                """.formatted(schema), id, prefix + "_" + id.toString().substring(0, 8), "test-only", prefix);
        return id;
    }

    private UUID insertProject(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO %s.project (id, name, owner_id, created_by)
                VALUES (?, ?, ?, ?)
                """.formatted(schema), id, "Migration Test", ownerId, ownerId);
        return id;
    }

    private void insertMember(UUID projectId, UUID userId, String role) {
        jdbcTemplate.update("""
                INSERT INTO %s.project_member (project_id, user_id, role)
                VALUES (?, ?, ?)
                """.formatted(schema), projectId, userId, role);
    }
}
