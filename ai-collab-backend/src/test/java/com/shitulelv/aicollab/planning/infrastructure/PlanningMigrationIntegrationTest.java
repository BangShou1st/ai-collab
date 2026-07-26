package com.shitulelv.aicollab.planning.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PlanningMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void v5EnforcesImmutableVersionAndConfirmationKeysAndDeletesFailedConfirmation() {
        UUID user = UUID.randomUUID(), project = UUID.randomUUID(), plan = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)
                """, user, "p8-" + user.toString().substring(0, 8), "test-only-hash", "Phase 08");
        jdbc.update("""
                INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)
                """, project, "Phase 08", user, user);
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,plan_start_date,plan_due_date,
                  max_task_count,status,created_by) VALUES (?,?,?,?,DATE '2026-08-01',DATE '2026-08-31',10,'READY',?)
                """, plan, project, "Plan", "Goal", user);

        UUID version = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,generation_seq,created_by)
                VALUES (?,?,1,'MANUAL_EDIT',1,?)
                """, version, plan, user);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_task_plan_version(id,plan_id,version_no,source_type,generation_seq,created_by)
                VALUES (?,?,1,'RESTORED',1,?)
                """, UUID.randomUUID(), plan, user)).isInstanceOf(Exception.class);

        jdbc.update("""
                INSERT INTO ai_task_plan_confirmation(project_id,plan_id,version_id,idempotency_key,
                  request_hash,status,created_by) VALUES (?,?,?,?,?,'FAILED',?)
                """, project, plan, version, UUID.randomUUID(), "0".repeat(64), user);
        jdbc.update("DELETE FROM ai_task_plan WHERE id=?", plan);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_plan_confirmation WHERE plan_id=?", Integer.class, plan)).isZero();
    }
}
