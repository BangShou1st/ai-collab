package com.shitulelv.aicollab.infrastructure.ai.user;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ProjectModelToUserMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;

    static UUID ownerSingle;
    static UUID ownerMulti;
    static UUID ownerConsistent;
    static UUID singleConfig;
    static UUID multiConfigA;
    static UUID multiConfigB;
    static UUID consistentConfigA;
    static UUID consistentConfigB;

    @BeforeAll
    static void migrate41ThenSeedThen42() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("41").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        seedLegacy();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    private static void seedLegacy() {
        ownerSingle = newUser("single");
        UUID solo = newProject(ownerSingle, "Solo");
        singleConfig = newConfig(solo, "main", "gpt-4o-mini");
        assign(solo, "KNOWLEDGE_CHAT", singleConfig);

        ownerMulti = newUser("multi");
        UUID pa = newProject(ownerMulti, "Alpha");
        UUID pb = newProject(ownerMulti, "Beta");
        multiConfigA = newConfig(pa, "main", "gpt-4o-mini");
        multiConfigB = newConfig(pb, "main", "claude-sonnet");
        assign(pa, "AGENT", multiConfigA);
        assign(pb, "AGENT", multiConfigB);

        ownerConsistent = newUser("consistent");
        UUID pc = newProject(ownerConsistent, "One");
        UUID pd = newProject(ownerConsistent, "Two");
        consistentConfigA = newConfig(pc, "main", "gpt-4o-mini");
        consistentConfigB = newConfig(pd, "other", "gpt-4o-mini");
        assign(pc, "PLANNING", consistentConfigA);
    }

    private static UUID newUser(String tag) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "mig-" + tag + "-" + user.toString().substring(0, 8), "test-only-hash", "Mig");
        return user;
    }

    private static UUID newProject(UUID owner, String name) {
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, name, owner, owner);
        return project;
    }

    private static UUID newConfig(UUID project, String name, String model) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO model_configuration(id,project_id,name,provider_type,base_url,api_path,
                  encrypted_api_key,model_name,enabled,temperature,max_output_tokens,capabilities)
                VALUES (?,?,?,'OPENAI_COMPATIBLE','https://api.openai.com','/v1/chat/completions',
                  'enc',?,TRUE,0.2,1200,'CHAT')
                """, id, project, name, model);
        return id;
    }

    private static void assign(UUID project, String purpose, UUID config) {
        jdbc.update("""
                INSERT INTO model_purpose_assignment(project_id,purpose,model_configuration_id)
                VALUES (?,?,?)
                """, project, purpose, config);
    }

    @Test
    void single_provider_becomes_default() {
        List<Boolean> defaults = jdbc.query(
                "SELECT is_default FROM user_ai_provider WHERE user_id=?", (rs, i) -> rs.getBoolean(1),
                ownerSingle);
        assertThat(defaults).containsExactly(true);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_model_purpose_assignment WHERE user_id=? AND purpose='KNOWLEDGE_CHAT'",
                Integer.class, ownerSingle)).isOne();
    }

    @Test
    void multiple_providers_leave_default_unset() {
        List<Boolean> defaults = jdbc.query(
                "SELECT is_default FROM user_ai_provider WHERE user_id=? ORDER BY name", (rs, i) -> rs.getBoolean(1),
                ownerMulti);
        assertThat(defaults).containsExactly(false, false);
    }

    @Test
    void conflicting_project_assignments_are_left_unset() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_model_purpose_assignment WHERE user_id=?", Integer.class,
                ownerMulti)).isZero();
    }

    @Test
    void all_project_providers_are_preserved() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_ai_provider WHERE user_id=?", Integer.class, ownerMulti))
                .isEqualTo(2);
        List<String> names = jdbc.query("SELECT name FROM user_ai_provider WHERE user_id=? ORDER BY name",
                (rs, i) -> rs.getString(1), ownerMulti);
        assertThat(names).allSatisfy(name -> assertThat(name).isNotBlank());
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void consistent_project_assignments_are_migrated() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_model_purpose_assignment WHERE user_id=? AND purpose='PLANNING'",
                Integer.class, ownerConsistent)).isOne();
    }

    @Test
    void migration_is_deterministic() {
        UUID expectedSingle = jdbc.queryForObject("SELECT md5('v42:' || ?)::uuid", UUID.class,
                singleConfig.toString());
        List<UUID> ids = jdbc.query("SELECT id FROM user_ai_provider WHERE user_id=?",
                (rs, i) -> rs.getObject(1, UUID.class), ownerSingle);
        assertThat(ids).containsExactly(expectedSingle);
        UUID expectedA = jdbc.queryForObject("SELECT md5('v42:' || ?)::uuid", UUID.class,
                multiConfigA.toString());
        UUID expectedB = jdbc.queryForObject("SELECT md5('v42:' || ?)::uuid", UUID.class,
                multiConfigB.toString());
        assertThat(jdbc.query("SELECT id FROM user_ai_provider WHERE user_id=? ORDER BY name",
                (rs, i) -> rs.getObject(1, UUID.class), ownerMulti))
                .containsExactlyInAnyOrder(expectedA, expectedB);
    }
}
