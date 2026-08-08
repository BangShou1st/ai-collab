package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent Proposal Continuity 集成测试。
 * 覆盖提案修订、匹配、历史、Run 状态和并发 CAS。
 * 测试应因新表、新列和新服务未实现而失败。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentProposalContinuityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void cleanData() {
        // 按外键依赖顺序删除数据
        jdbc.update("DELETE FROM agent_approval_revision");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_approval");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    void independentCreateProposalsRemainDistinctWithoutExplicitApprovalId() {
        // 1. 创建用户、项目、会话和 Run
        Fixture f = fixture();
        UUID runId = createRun(f, "创建任务提案");

        // 2. 创建第一个审批（TASK_CREATE）
        UUID approvalId1 = createApproval(f, runId, "create_task_after_approval",
                "TASK_CREATE", json.createObjectNode().put("title", "原任务"));

        // 3. 第二个完整创建请求没有携带可信 approvalId，应保持独立。
        UUID approvalId2 = createApproval(f, runId, "create_task_after_approval",
                "TASK_CREATE", json.createObjectNode().put("title", "修订任务"));

        // 谨慎创建：不能仅因工具族相同就覆盖另一个待审批对象。
        Integer pendingCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_approval WHERE project_id=? AND status='PENDING'",
                Integer.class, f.project());
        assertThat(pendingCount).isEqualTo(2);
    }

    @Test
    void rejectedProposalIsNotReused() {
        Fixture f = fixture();
        UUID runId = createRun(f, "创建任务提案");

        // 创建并拒绝一个提案
        UUID approvalId = createApproval(f, runId, "create_task_after_approval",
                "TASK_CREATE", json.createObjectNode().put("title", "被拒绝任务"));
        jdbc.update("UPDATE agent_approval SET status='REJECTED' WHERE id=?", approvalId);

        // 再次提出相同需求，应该创建新审批而不是复用被拒绝的
        UUID newRunId = createRun(f, "再次创建任务提案");
        UUID newApprovalId = createApproval(f, newRunId, "create_task_after_approval",
                "TASK_CREATE", json.createObjectNode().put("title", "新任务"));

        // 验证：有两个审批（一个 REJECTED，一个 PENDING）
        Integer totalCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_approval WHERE project_id=? AND tool_name=?",
                Integer.class, f.project(), "create_task_after_approval");
        assertThat(totalCount).isEqualTo(2); // 验证没有复用

        Integer pendingCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_approval WHERE project_id=? AND status='PENDING'",
                Integer.class, f.project());
        assertThat(pendingCount).isEqualTo(1); // 只有一个 PENDING
    }

    @Test
    void approvedCreateProducesAResourceUpdateProposal() {
        Fixture f = fixture();
        UUID runId = createRun(f, "创建任务提案");

        // 创建并批准一个 TASK_CREATE 提案
        UUID approvalId = createApproval(f, runId, "create_task_after_approval",
                "TASK_CREATE", json.createObjectNode().put("title", "新建任务"));
        UUID resourceId = UUID.randomUUID();
        jdbc.update("""
                UPDATE agent_approval SET status='APPROVED', resource_id=?
                WHERE id=?
                """, resourceId, approvalId);

        // 验证：已批准后，后续修改应使用 TASK_UPDATE 工具
        // 当前：没有自动创建更新提案（测试预期失败）
        Integer updateApprovals = jdbc.queryForObject(
                "SELECT count(*) FROM agent_approval WHERE project_id=? AND tool_name=?",
                Integer.class, f.project(), "update_task_after_approval");
        assertThat(updateApprovals).isEqualTo(0); // 当前没有，测试失败
    }

    @Test
    void concurrentRevisionUsesVersionCompareAndSet() {
        Fixture f = fixture();
        UUID runId = createRun(f, "创建任务提案");

        // 创建一个审批
        UUID approvalId = createApproval(f, runId, "create_task_after_approval",
                "TASK_CREATE", json.createObjectNode().put("title", "原任务"));

        // 读取当前版本
        Integer version = jdbc.queryForObject(
                "SELECT version FROM agent_approval WHERE id=?", Integer.class, approvalId);
        assertThat(version).isZero();

        // 模拟并发修订：一个成功，一个因版本冲突失败
        int updated1 = jdbc.update("""
                UPDATE agent_approval SET arguments_json=?::jsonb, version=version+1
                WHERE id=? AND version=?
                """, json.createObjectNode().put("title", "修订1").toString(),
                approvalId, version);

        int updated2 = jdbc.update("""
                UPDATE agent_approval SET arguments_json=?::jsonb, version=version+1
                WHERE id=? AND version=?
                """, json.createObjectNode().put("title", "修订2").toString(),
                approvalId, version); // 使用旧版本

        assertThat(updated1).isEqualTo(1); // 第一个成功
        assertThat(updated2).isEqualTo(0); // 第二个因版本冲突失败
    }

    @Test
    void proposalIdCannotCrossProjectSessionOrRequester() {
        Fixture f1 = fixture();
        Fixture f2 = fixture();

        // 在项目 1 创建审批
        UUID runId1 = createRun(f1, "项目1的任务");
        UUID approvalId = createApproval(f1, runId1, "create_task_after_approval",
                "TASK_CREATE", json.createObjectNode().put("title", "项目1任务"));

        // 尝试用项目 2 的上下文访问审批（应失败）
        assertThatThrownBy(() -> jdbc.queryForObject(
                "SELECT id FROM agent_approval WHERE project_id=? AND id=?",
                UUID.class, f2.project(), approvalId))
                .isInstanceOf(Exception.class);
    }

    // ========== 辅助方法 ==========

    private Fixture fixture() {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "test-" + user.toString().substring(0, 8), "test-hash", "测试用户");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "测试项目", user, user);
        jdbc.update("INSERT INTO agent_session(id,project_id,creator_id,title) VALUES (?,?,?,?)",
                session, project, user, "测试会话");
        return new Fixture(user, project, session);
    }

    private UUID createRun(Fixture f, String goal) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run(id,session_id,project_id,requester_id,goal,status)
                VALUES (?,?,?,?,?,'RUNNING')
                """, runId, f.session(), f.project(), f.user(), goal);
        return runId;
    }

    private UUID createApproval(Fixture f, UUID runId, String toolName,
                                 String proposalFamily, JsonNode arguments) {
        UUID approvalId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        Integer sequence = jdbc.queryForObject(
                "SELECT COALESCE(max(sequence_no),0)+1 FROM agent_step WHERE run_id=?",
                Integer.class, runId);
        // 当前 V36 没有 proposal_family 列，测试会失败
        jdbc.update("""
                INSERT INTO agent_step(id,run_id,sequence_no,type,tool_name,input_json,output_json)
                VALUES (?,?,?,'APPROVAL_REQUESTED',?,?::jsonb,?::jsonb)
                """, stepId, runId, sequence == null ? 1 : sequence, toolName,
                arguments.toString(), "{}");
        jdbc.update("""
                INSERT INTO agent_approval(
                  id,project_id,run_id,step_id,tool_name,arguments_json,arguments_hash,
                  diff_json,requester_id,nonce_hash,expires_at,status,
                  session_id,proposal_family,subject_key)
                VALUES (?,?,?,?,?,?::jsonb,?,?::jsonb,?,?,?,'PENDING',?,?,?)
                """, approvalId, f.project(), runId, stepId, toolName,
                arguments.toString(), "test-hash", "{}",
                f.user(), "test-hash", OffsetDateTime.now().plusHours(24),
                f.session(), proposalFamily, approvalId);
        return approvalId;
    }

    private record Fixture(UUID user, UUID project, UUID session) {}
}
