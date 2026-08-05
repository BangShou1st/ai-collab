package com.shitulelv.aicollab.planning.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers(disabledWithoutDocker = true)
class Phase08MigrationSafetyIntegrationTest {
    private static final int PUBLISHED_V5_CHECKSUM = -1995940233;
    private static final int DRIFTED_V5_CHECKSUM = 1512947011;
    private static final String GUARD_MESSAGE =
            "Phase 04 planning data must be exported or migrated before applying V5";
    private static final Map<String, String> PUBLISHED_MIGRATION_SHA256 = publishedMigrationHashes();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Test
    void emptyDatabaseMigratesFromV1ThroughV6() {
        Database database = newDatabase();

        migrate(database.dataSource(), null);

        assertSuccessfulHistoryThroughV6(database.jdbc());
    }

    @Test
    void emptyV4PlaceholderTablesMigrateThroughV6() {
        Database database = newDatabase();
        migrate(database.dataSource(), "4");
        assertThat(database.jdbc().queryForObject(
                "SELECT count(*) FROM ai_task_plan", Integer.class)).isZero();

        migrate(database.dataSource(), null);

        assertSuccessfulHistoryThroughV6(database.jdbc());
        assertThat(database.jdbc().queryForObject(
                "SELECT to_regclass('ai_task_plan_version') IS NOT NULL", Boolean.class)).isTrue();
    }

    @Test
    void missingV4PlaceholderTablesMigrateThroughV6() {
        Database database = newDatabase();
        migrate(database.dataSource(), "4");
        database.jdbc().execute("""
                DROP TABLE ai_task_plan_dependency, ai_task_plan_task,
                  ai_task_plan_milestone, ai_task_plan
                """);

        migrate(database.dataSource(), null);

        assertSuccessfulHistoryThroughV6(database.jdbc());
    }

    @Test
    void v4PlanningRowsBlockBeforeV5AndRemainIntact() {
        Database database = newDatabase();
        migrate(database.dataSource(), "4");
        insertLegacyPlanningGraph(database.jdbc());

        Throwable failure = catchThrowable(() -> migrate(database.dataSource(), null));

        assertThat(failure)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(GUARD_MESSAGE);
        assertThat(database.jdbc().queryForObject(
                "SELECT max(version::integer) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(4);
        assertThat(database.jdbc().queryForObject("SELECT count(*) FROM ai_task_plan", Integer.class)).isOne();
        assertThat(database.jdbc().queryForObject("SELECT count(*) FROM ai_task_plan_milestone", Integer.class)).isOne();
        assertThat(database.jdbc().queryForObject("SELECT count(*) FROM ai_task_plan_task", Integer.class)).isOne();
        assertThat(database.jdbc().queryForObject("SELECT count(*) FROM ai_task_plan_dependency", Integer.class)).isOne();
    }

    @Test
    void publishedV5MigratesDirectlyToV6AndV6OwnsCascadeRepair() {
        Database database = newDatabase();
        migrate(database.dataSource(), "5");

        assertThat(checksum(database.jdbc(), "5")).isEqualTo(PUBLISHED_V5_CHECKSUM);
        assertThat(checksum(database.jdbc(), "5")).isNotEqualTo(DRIFTED_V5_CHECKSUM);
        assertThat(confirmationPlanDeleteAction(database.jdbc())).isEqualTo("r");

        migrate(database.dataSource(), null);

        assertSuccessfulHistoryThroughV6(database.jdbc());
        assertThat(confirmationPlanDeleteAction(database.jdbc())).isEqualTo("c");
    }

    @Test
    void publishedV1ThroughV5ResourcesRemainByteForByteImmutable() {
        PUBLISHED_MIGRATION_SHA256.forEach((resource, expectedSha256) ->
                assertThat(resourceSha256(resource))
                        .as("%s must remain byte-for-byte identical to d7897ab", resource)
                        .isEqualTo(expectedSha256));
    }

    private static void migrate(DataSource dataSource, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .callbacks(new Phase08LegacyPlanningDataGuard());
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static Database newDatabase() {
        String name = "phase08_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .execute("CREATE DATABASE " + name);
        String jdbcUrl = POSTGRES.getJdbcUrl().replaceFirst("/test(\\?|$)", "/" + name + "$1");
        var dataSource = new DriverManagerDataSource(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        return new Database(dataSource, new JdbcTemplate(dataSource));
    }

    private static void insertLegacyPlanningGraph(JdbcTemplate jdbc) {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name)
                VALUES (?,?,?,?)
                """, userId, "migration-" + userId.toString().substring(0, 8), "test-only-hash", "Migration");
        jdbc.update("""
                INSERT INTO project(id,name,owner_id,created_by)
                VALUES (?,?,?,?)
                """, projectId, "Legacy planning", userId, userId);
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,created_by,goal,start_date,due_date,max_tasks)
                VALUES (?,?,?,'Preserve me',DATE '2026-08-01',DATE '2026-08-31',10)
                """, planId, projectId, userId);
        jdbc.update("""
                INSERT INTO ai_task_plan_milestone(plan_id,temp_key,name)
                VALUES (?,'m1','Legacy milestone')
                """, planId);
        jdbc.update("""
                INSERT INTO ai_task_plan_task(plan_id,temp_key,milestone_temp_key,title,priority)
                VALUES (?,'t1','m1','Legacy task','MEDIUM')
                """, planId);
        jdbc.update("""
                INSERT INTO ai_task_plan_dependency(plan_id,task_temp_key,depends_on_temp_key)
                VALUES (?,'t1','t0')
                """, planId);
    }

    private static void assertSuccessfulHistoryThroughV6(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT version FROM flyway_schema_history
                WHERE type <> 'SCHEMA' ORDER BY installed_rank
                """, String.class)).containsExactly(
                        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30");
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(success) FROM flyway_schema_history
                WHERE type <> 'SCHEMA'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'ai_task_plan_event'
                  AND column_name = 'changed_targets_json'
                  AND data_type = 'jsonb'
                """, Integer.class)).isEqualTo(1);
        assertThat(checksum(jdbc, "5")).isEqualTo(PUBLISHED_V5_CHECKSUM);
    }

    private static int checksum(JdbcTemplate jdbc, String version) {
        return jdbc.queryForObject("""
                SELECT checksum FROM flyway_schema_history WHERE version=?
                """, Integer.class, version);
    }

    private static String confirmationPlanDeleteAction(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT confdeltype::text
                FROM pg_constraint
                WHERE conname='ai_task_plan_confirmation_plan_id_fkey'
                  AND conrelid='ai_task_plan_confirmation'::regclass
                """, String.class);
    }

    private static String resourceSha256(String resource) {
        try (InputStream input = Phase08MigrationSafetyIntegrationTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration resource " + resource);
            }
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash migration resource " + resource, exception);
        }
    }

    private static Map<String, String> publishedMigrationHashes() {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("/db/migration/V1__init_schema.sql",
                "25D8F3F64E5E7F1C9CAC999B940C3D840F5F7E7F15155A8058CAB6EE5047EB07");
        hashes.put("/db/migration/V2__extend_refresh_token_session.sql",
                "295BA5C94EB969EA9AB2D27EA47B7F38FDB9F2346091BF950275ABF51BB61E6D");
        hashes.put("/db/migration/V3__enforce_single_project_owner.sql",
                "D12C3D4493B2A06428A3FA4269C2F164874CC65F50C4F1FC59E77086CF0BA61E");
        hashes.put("/db/migration/V4__document_processing_attempt.sql",
                "682B412BF50A32396AB88A42CD147725C17026C06F2786C523B1BD5075487EDC");
        hashes.put("/db/migration/V5__create_ai_task_planning.sql",
                "5C9608869B3D5BCF3636FAC9A79F4BD968F4F6BA1F9B1068129E115AEAEB32A2");
        return Map.copyOf(hashes);
    }

    private record Database(DataSource dataSource, JdbcTemplate jdbc) {
    }
}
