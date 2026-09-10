package com.shitulelv.aicollab.infrastructure.ai.embedding;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class LegacyEmbeddingMigrationTest {

    private JdbcTemplate migrateTo42() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
        postgres.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("42").load().migrate();
        return new JdbcTemplate(dataSource);
    }

    private void migrateLatest(JdbcTemplate jdbc) {
        Flyway.configure()
                .dataSource((DriverManagerDataSource) jdbc.getDataSource())
                .locations("classpath:db/migration").load().migrate();
    }

    private static UUID newUser(JdbcTemplate jdbc, String tag) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "emb-" + tag + "-" + user.toString().substring(0, 8), "test-only-hash", "Emb");
        return user;
    }

    private static UUID newProject(JdbcTemplate jdbc, UUID owner, String name) {
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, name, owner, owner);
        return project;
    }

    private static void newEmbeddingConfig(JdbcTemplate jdbc, UUID project, String baseUrl, String model,
            int dimensions, String key) {
        jdbc.update("""
                INSERT INTO project_embedding_config(project_id,provider,base_url,encrypted_api_key,
                  model_name,dimensions)
                VALUES (?,'OPENAI_COMPATIBLE',?,?,?,?)
                """, project, baseUrl, key, model, dimensions);
    }

    @Test
    void case_a_no_legacy_config_leaves_system_unconfigured() {
        JdbcTemplate jdbc = migrateTo42();
        UUID owner = newUser(jdbc, "a");
        newProject(jdbc, owner, "Empty");
        migrateLatest(jdbc);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM system_embedding_config", Integer.class)).isZero();
    }

    @Test
    void case_b_consistent_semantic_is_promoted_without_key() {
        JdbcTemplate jdbc = migrateTo42();
        UUID owner = newUser(jdbc, "b");
        UUID pa = newProject(jdbc, owner, "P1");
        UUID pb = newProject(jdbc, owner, "P2");
        newEmbeddingConfig(jdbc, pa, "https://api.openai.com", "text-embedding-3-small", 1536, "k1");
        newEmbeddingConfig(jdbc, pb, "https://api.openai.com", "text-embedding-3-small", 1536, "k2");
        migrateLatest(jdbc);

        List<Boolean> enabled = jdbc.query("SELECT enabled FROM system_embedding_config",
                (rs, i) -> rs.getBoolean(1));
        assertThat(enabled).containsExactly(false);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM system_embedding_config WHERE encrypted_api_key IS NOT NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM system_embedding_config WHERE model_name=? AND dimensions=?",
                Integer.class, "text-embedding-3-small", 1536)).isOne();
    }

    @Test
    void case_c_conflicting_semantics_leave_system_unconfigured() {
        JdbcTemplate jdbc = migrateTo42();
        UUID owner = newUser(jdbc, "c");
        UUID pa = newProject(jdbc, owner, "P1");
        UUID pb = newProject(jdbc, owner, "P2");
        newEmbeddingConfig(jdbc, pa, "https://api.openai.com", "text-embedding-3-small", 1536, "k1");
        newEmbeddingConfig(jdbc, pb, "https://other.example", "other-model", 768, "k2");
        migrateLatest(jdbc);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM system_embedding_config", Integer.class)).isZero();
    }
}
