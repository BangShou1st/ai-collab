package com.shitulelv.aicollab.agent.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AgentMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsCompleteAgentSchema() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='public' AND table_name LIKE 'agent_%'
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly(
                "agent_approval",
                "agent_mcp_connection",
                "agent_memory",
                "agent_message",
                "agent_project_mcp_binding",
                "agent_run",
                "agent_run_event",
                "agent_schedule",
                "agent_schedule_fire",
                "agent_session",
                "agent_step");
    }

    @Test
    void enforcesStepAndScheduleFireIdempotency() {
        Fixture fixture = fixture();
        UUID run = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run(id,session_id,project_id,requester_id,goal,status)
                VALUES (?,?,?,?,?,'QUEUED')
                """, run, fixture.session(), fixture.project(), fixture.user(), "检查项目");
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type) VALUES (?,1,'MODEL_REQUEST')
                """, run);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type) VALUES (?,1,'ERROR')
                """, run)).isInstanceOf(Exception.class);

        UUID schedule = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_schedule(
                  id,project_id,creator_id,session_id,name,goal,frequency,time_zone,
                  local_time,next_fire_at)
                VALUES (?,?,?,?,?,?,'DAILY','Asia/Shanghai','08:00',now())
                """, schedule, fixture.project(), fixture.user(), fixture.session(), "晨报", "检查进度");
        jdbc.update("""
                INSERT INTO agent_schedule_fire(schedule_id,scheduled_for,run_id)
                VALUES (?,TIMESTAMPTZ '2026-07-30 00:00:00+00',?)
                """, schedule, run);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_schedule_fire(schedule_id,scheduled_for,run_id)
                VALUES (?,TIMESTAMPTZ '2026-07-30 00:00:00+00',?)
                """, schedule, run)).isInstanceOf(Exception.class);
    }

    @Test
    void scopesMemoriesAndMcpBindingsByProject() {
        Fixture first = fixture();
        Fixture second = fixture();
        jdbc.update("""
                INSERT INTO agent_memory(project_id,type,title,content,source_type,created_by,updated_by)
                VALUES (?,'DECISION','A 决策','仅属于 A','USER',?,?)
                """, first.project(), first.user(), first.user());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_memory WHERE project_id=?", Integer.class, first.project())).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_memory WHERE project_id=?", Integer.class, second.project())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT skill_code IS NULL FROM agent_schedule LIMIT 1", Boolean.class)).isTrue();
    }

    private static Fixture fixture() {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "agent-" + user.toString().substring(0, 8), "test-only-hash", "Agent");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "Agent project", user, user);
        jdbc.update("""
                INSERT INTO agent_session(id,project_id,creator_id,title)
                VALUES (?,?,?,?)
                """, session, project, user, "协作会话");
        return new Fixture(user, project, session);
    }

    private record Fixture(UUID user, UUID project, UUID session) {
    }
}
