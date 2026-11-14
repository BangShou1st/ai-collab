package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentDelegationLedgerRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 委派防重台账（V81）集成回归（真实 PostgreSQL）：
 * 同一会话下同一 objective（归一化哈希）的重复委派被拒绝且不创建第二个子运行；
 * 子运行终态（成功/失败/取消）后台账 status 正确收口；取消放行同目标再次委派。
 * 不依赖真实模型：直接走仓储的两条委派受理路径。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentDelegationLedgerPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static AgentRunEventRecorder recorder;
    static AgentRepository repository;
    static AgentDelegationLedgerRepository ledger;

    private record Fixture(UUID user, UUID project, UUID session) {}

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        recorder = new AgentRunEventRecorder(jdbc, json);
        repository = new AgentRepository(jdbc, json, recorder);
        ledger = new AgentDelegationLedgerRepository(jdbc);
    }

    @BeforeEach
    void setUp() {
        // 台账对 agent_run/agent_session 级联删除，这里显式清理兜底
        jdbc.update("DELETE FROM agent_delegation_ledger");
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
    }

    private Fixture fixture() {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "ledger-" + user.toString().substring(0, 8), "test-only-hash", "Ledger");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "委派台账验收", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        var session = repository.createSession(project, user, "委派台账验收");
        return new Fixture(user, project, session.id());
    }

    /** 创建并领取一个 v1 兼容父运行（委派受理路径的最小夹具）。 */
    private AgentRunView claimedParent(Fixture fixture, String goal, String worker) {
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                goal, false, null, null);
        jdbc.update("""
                UPDATE agent_run SET context_policy_version=1, max_steps=12, max_tool_calls=8,
                  max_input_tokens=50000, max_output_tokens=20000 WHERE id=?
                """, run.id());
        repository.claimNext(worker, Duration.ofMinutes(6)).orElseThrow();
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    private List<String> ledgerStatuses(UUID sessionId) {
        return jdbc.queryForList("""
                SELECT status FROM agent_delegation_ledger WHERE session_id=? ORDER BY id
                """, String.class, sessionId);
    }

    /** 新路径：同 objective 的第二次委派被拒（稳定拒绝码），且不创建第二个子运行。 */
    @Test
    void duplicateObjectiveDelegationIsRejectedWithoutSecondChild() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档", "w-ledger-1");
        String objective = "Research the acceptance criteria with sources";
        var first = repository.documentResearchDelegationResult(
                parent, UUID.randomUUID().toString(), objective);
        assertThat(first.path("status").asText()).isEqualTo("DELEGATED");
        UUID childId = UUID.fromString(first.path("childRunId").asText());
        // 台账已登记 RUNNING
        assertThat(ledgerStatuses(fixture.session())).containsExactly("RUNNING");
        assertThat(jdbc.queryForObject(
                "SELECT objective_hash FROM agent_delegation_ledger WHERE child_run_id=?",
                String.class, childId)).isEqualTo(
                com.shitulelv.aicollab.agent.domain.model.AgentDelegationGuard.objectiveHash(objective));

        // 同 objective 第二次委派（新 invocation）：拒绝受理
        assertThatThrownBy(() -> repository.documentResearchDelegationResult(
                repository.findRun(fixture.project(), parent.id()).orElseThrow(),
                UUID.randomUUID().toString(), objective))
                .isInstanceOf(AgentDelegationNotAdmittedException.class)
                .satisfies(e -> assertThat(((AgentDelegationNotAdmittedException) e).reasonCode())
                        .isEqualTo("AGENT_DELEGATION_DUPLICATE_OBJECTIVE"));
        // 不创建第二个子运行、台账不新增记录、children_used 不增加
        assertThat(repository.childRuns(fixture.project(), parent.id())).hasSize(1);
        assertThat(ledgerStatuses(fixture.session())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT children_used FROM agent_run WHERE id=?",
                Integer.class, parent.id())).isEqualTo(1);
    }

    /** 新路径：objective 的大小写/标点/空白变体同样命中台账判重。 */
    @Test
    void objectiveVariantsAlsoHitLedgerDedup() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档", "w-ledger-2");
        repository.documentResearchDelegationResult(parent, UUID.randomUUID().toString(),
                "Research the acceptance criteria with sources");
        assertThatThrownBy(() -> repository.documentResearchDelegationResult(
                repository.findRun(fixture.project(), parent.id()).orElseThrow(),
                UUID.randomUUID().toString(),
                "  RESEARCH, the acceptance criteria!! with sources... "))
                .isInstanceOf(AgentDelegationNotAdmittedException.class)
                .satisfies(e -> assertThat(((AgentDelegationNotAdmittedException) e).reasonCode())
                        .isEqualTo("AGENT_DELEGATION_DUPLICATE_OBJECTIVE"));
        assertThat(repository.childRuns(fixture.project(), parent.id())).hasSize(1);
    }

    /** 子运行终态后台账收口：成功→COMPLETED、最终失败→FAILED。 */
    @Test
    void childTerminalStatesCloseLedgerStatus() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档", "w-ledger-3");
        // 子运行 1：正常完成
        var first = repository.documentResearchDelegationResult(parent,
                UUID.randomUUID().toString(), "Research objective one");
        UUID child1 = UUID.fromString(first.path("childRunId").asText());
        repository.claimNext("w-ledger-3-child1", Duration.ofMinutes(6)).orElseThrow();
        repository.recordFinal(repository.findRun(fixture.project(), child1).orElseThrow(),
                "研究发现", List.of());
        assertThat(ledgerStatuses(fixture.session())).containsExactly("COMPLETED");

        // 子运行 2：最终失败（不可重试）。父运行被子运行 1 唤醒为 QUEUED，重新领取进入 RUNNING
        repository.claimNext("w-ledger-3-parent", Duration.ofMinutes(6)).orElseThrow();
        parent = repository.findRun(fixture.project(), parent.id()).orElseThrow();
        var second = repository.documentResearchDelegationResult(parent,
                UUID.randomUUID().toString(), "Research objective two");
        UUID child2 = UUID.fromString(second.path("childRunId").asText());
        repository.claimNext("w-ledger-3-child2", Duration.ofMinutes(6)).orElseThrow();
        repository.recordFailure(repository.findRun(fixture.project(), child2).orElseThrow(),
                "MODEL_FAILED", false);
        assertThat(ledgerStatuses(fixture.session())).containsExactly("COMPLETED", "FAILED");
    }

    /** 取消的委派不阻止同 objective 再次委派（CANCELED 不算现存记录），新台账为 RUNNING。 */
    @Test
    void canceledChildReleasesObjectiveForRedelegation() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档", "w-ledger-4");
        String objective = "Research the acceptance criteria with sources";
        var first = repository.documentResearchDelegationResult(parent,
                UUID.randomUUID().toString(), objective);
        UUID child = UUID.fromString(first.path("childRunId").asText());
        // 取消子运行（必须先领取进入 RUNNING）
        repository.claimNext("w-ledger-4-child", Duration.ofMinutes(6)).orElseThrow();
        repository.recordCanceled(repository.findRun(fixture.project(), child).orElseThrow());
        assertThat(ledgerStatuses(fixture.session())).containsExactly("CANCELED");

        // 同 objective 再次委派放行：父运行被唤醒为 QUEUED，重新领取后受理成功
        repository.claimNext("w-ledger-4-parent", Duration.ofMinutes(6)).orElseThrow();
        var second = repository.documentResearchDelegationResult(
                repository.findRun(fixture.project(), parent.id()).orElseThrow(),
                UUID.randomUUID().toString(), objective);
        assertThat(second.path("status").asText()).isEqualTo("DELEGATED");
        assertThat(second.path("childRunId").asText())
                .as("取消后重新委派创建新的子运行").isNotEqualTo(child.toString());
        assertThat(repository.childRuns(fixture.project(), parent.id())).hasSize(2);
        assertThat(ledgerStatuses(fixture.session())).containsExactly("CANCELED", "RUNNING");
    }

    /** 旧路径 recordDelegation：同样登记台账并拒绝同 objective 的重复委派。 */
    @Test
    void legacyDelegationPathAlsoWritesLedgerAndRejectsDuplicate() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档", "w-ledger-5");
        var completion = new ChatCompletionResult(
                "委托", "OPENAI_COMPATIBLE", "model-a", 100, 20, 50L);
        var delegate = new AgentDecision.Delegate("KNOWLEDGE_RESEARCHER", "研究验收标准");
        AgentRunView child = repository.recordDelegation(parent, completion, delegate);
        assertThat(ledgerStatuses(fixture.session())).containsExactly("RUNNING");
        assertThat(jdbc.queryForObject(
                "SELECT objective_hash FROM agent_delegation_ledger WHERE child_run_id=?",
                String.class, child.id())).isEqualTo(
                com.shitulelv.aicollab.agent.domain.model.AgentDelegationGuard.objectiveHash("研究验收标准"));

        // 同 objective 的第二次旧路径委派：拒绝且不创建第二个子运行
        assertThatThrownBy(() -> repository.recordDelegation(
                repository.findRun(fixture.project(), parent.id()).orElseThrow(),
                completion, delegate))
                .isInstanceOf(AgentDelegationNotAdmittedException.class)
                .satisfies(e -> assertThat(((AgentDelegationNotAdmittedException) e).reasonCode())
                        .isEqualTo("AGENT_DELEGATION_DUPLICATE_OBJECTIVE"));
        assertThat(repository.childRuns(fixture.project(), parent.id())).hasSize(1);
        assertThat(ledgerStatuses(fixture.session())).hasSize(1);
    }

    /** 台账仓储的直接契约：RUNNING 记录参与判重，closeForChild 幂等且只收口 RUNNING。 */
    @Test
    void ledgerRepositoryContract() {
        Fixture fixture = fixture();
        // 台账有外键约束：parent/child 必须是真实 agent_run 行
        AgentRunView parent = repository.createRun(fixture.project(), fixture.session(),
                fixture.user(), "契约测试父运行", false, null, null);
        UUID child = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run(id,session_id,project_id,requester_id,parent_run_id,
                  role,depth,goal,status,max_children)
                VALUES (?,?,?,?,?, 'KNOWLEDGE_RESEARCHER',1,'契约测试子运行','QUEUED',0)
                """, child, fixture.session(), fixture.project(), fixture.user(), parent.id());
        String hash = com.shitulelv.aicollab.agent.domain.model.AgentDelegationGuard
                .objectiveHash("契约测试目标");
        assertThat(ledger.countActiveByObjective(fixture.session(), hash)).isZero();
        ledger.insertRunning(fixture.session(), parent.id(), child, hash);
        assertThat(ledger.countActiveByObjective(fixture.session(), hash)).isEqualTo(1);
        // 其他会话/其他哈希不误判
        assertThat(ledger.countActiveByObjective(UUID.randomUUID(), hash)).isZero();
        assertThat(ledger.countActiveByObjective(fixture.session(),
                com.shitulelv.aicollab.agent.domain.model.AgentDelegationGuard.objectiveHash("另一个目标")))
                .isZero();
        // 收口为 COMPLETED 后仍参与判重（非 CANCELED）
        ledger.closeForChild(child, "COMPLETED");
        assertThat(ledger.countActiveByObjective(fixture.session(), hash)).isEqualTo(1);
        // 重复收口 no-op
        ledger.closeForChild(child, "FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_delegation_ledger WHERE child_run_id=?",
                String.class, child)).isEqualTo("COMPLETED");
        // 只有 CANCELED 放行再次委派
        jdbc.update("UPDATE agent_delegation_ledger SET status='CANCELED' WHERE child_run_id=?", child);
        assertThat(ledger.countActiveByObjective(fixture.session(), hash)).isZero();
    }
}
