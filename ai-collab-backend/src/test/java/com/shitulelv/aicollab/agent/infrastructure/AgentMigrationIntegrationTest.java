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

        assertThat(tables).contains(
                "agent_approval",
                "agent_approval_revision", // V37 新增
                "agent_mcp_connection",
                "agent_memory",
                "agent_message",
                "agent_run",
                "agent_run_event",
                "agent_session",
                "agent_step");
    }

    /**
     * 验证 V37 迁移添加了提案连续性所需的新列和新表。
     * 测试因 V37 迁移尚未创建而失败。
     */
    @Test
    void v37AddsProposalContinuityColumns() {
        // 验证 agent_approval 新列
        List<String> approvalColumns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name='agent_approval' AND column_name IN
                ('session_id','proposal_family','subject_key','revision','updated_at')
                ORDER BY column_name
                """, String.class);

        // 当前 V36 没有这些列，测试会失败
        assertThat(approvalColumns).containsExactly(
                "proposal_family", "revision", "session_id", "subject_key", "updated_at");
    }

    /**
     * 验证 agent_approval_revision 表结构。
     * 测试因 V37 迁移尚未创建而失败。
     */
    @Test
    void v37CreatesApprovalRevisionTable() {
        List<String> revisionColumns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name='agent_approval_revision'
                ORDER BY ordinal_position
                """, String.class);

        // 当前 V36 没有此表，测试会失败
        assertThat(revisionColumns).containsExactly(
                "id", "project_id", "approval_id", "source_run_id",
                "revision", "before_arguments_json", "after_arguments_json",
                "diff_json", "created_at");
    }

    /**
     * 验证 ck_agent_run_event_type 约束包含 APPROVAL_UPDATED。
     * 测试因 V37 迁移尚未重建约束而失败。
     */
    @Test
    void v37EventConstraintIncludesApprovalUpdated() {
        // 查询约束定义
        String constraintDef = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname='ck_agent_run_event_type'
                """, String.class);

        // 当前 V36 约束不包含 APPROVAL_UPDATED，测试会失败
        assertThat(constraintDef).contains("APPROVAL_UPDATED");
    }

    @Test
    void enforcesStepIdempotency() {
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
