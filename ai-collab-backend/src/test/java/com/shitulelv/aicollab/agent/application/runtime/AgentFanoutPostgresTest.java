package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子 Agent 并行 fan-out 编排核心集成回归（真实 PostgreSQL，设计文档
 * docs/design/agent-fanout-budget-and-agentic-planning.md §3.1/§3.3/§3.4）：
 * 同一批次受理多个委派（children_pending 计数等待语义）、3 子并行交错终态
 * （完成/失败/取消）父只在全终态后恢复、部分失败分组注入、
 * 父运行接管时按存活子重算 pending、唤醒事件 afterCommit 才发布。
 * 不依赖真实模型：直接走仓储的委派受理路径与终态记录路径。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentFanoutPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static AgentRunEventRecorder recorder;
    static AgentRepository repository;
    static DataSource dataSource;

    private record Fixture(UUID user, UUID project, UUID session) {}

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        recorder = new AgentRunEventRecorder(jdbc, json);
        repository = new AgentRepository(jdbc, json, recorder);
    }

    @BeforeEach
    void setUp() {
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
                user, "fanout-" + user.toString().substring(0, 8), "test-only-hash", "Fanout");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "并行委派验收", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        var session = repository.createSession(project, user, "并行委派验收");
        return new Fixture(user, project, session.id());
    }

    /** 创建并领取一个 v2 默认父运行（并行 fan-out 夹具）。 */
    private AgentRunView claimedParent(Fixture fixture, String goal, String worker) {
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                goal, false, null, null);
        repository.claimNext(worker, Duration.ofMinutes(6)).orElseThrow();
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    /** 受理一个委派并返回子运行视图（每受理一次刷新父视图，模拟执行器的乐观锁纪律）。 */
    private AgentRunView admitDelegation(Fixture fixture, AgentRunView parent, String objective) {
        var result = repository.documentResearchDelegationResult(
                parent, UUID.randomUUID().toString(), objective);
        assertThat(result.path("status").asText()).isEqualTo("DELEGATED");
        return repository.findRun(fixture.project(),
                UUID.fromString(result.path("childRunId").asText())).orElseThrow();
    }

    /** 反复领取直到领到目标运行（depth 降序保证子运行先于父运行被领取）。 */
    private ClaimedAgentRun claimRun(Fixture fixture, UUID runId, String worker) {
        for (int i = 0; i < 20; i++) {
            var claimed = repository.claimNext(worker, Duration.ofMinutes(6)).orElseThrow();
            if (claimed.id().equals(runId)) return claimed;
        }
        throw new AssertionError("目标运行未被领取: " + runId);
    }

    private int pending(UUID runId) {
        Integer value = jdbc.queryForObject(
                "SELECT children_pending FROM agent_run WHERE id=?", Integer.class, runId);
        return value == null ? -1 : value;
    }

    private String parentStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM agent_run WHERE id=?", String.class, runId);
    }

    private List<JsonNode> completedPayloads(UUID parentId) {
        return jdbc.query("""
                SELECT output_json::text AS output_json FROM agent_step
                WHERE run_id=? AND type='DELEGATION_COMPLETED' ORDER BY sequence_no
                """, (rs, n) -> {
                    try { return json.readTree(rs.getString(1)); }
                    catch (Exception failed) { throw new IllegalStateException(failed); }
                }, parentId);
    }

    /**
     * 3 子并行 fan-out、交错终态（完成/失败/取消）：父运行在全部子终态前保持等待态
     * （RUNNING + pending>0，不被领取），只在最后一个子终态后重新排队；
     * DELEGATION_COMPLETED step 每子恰好一条（times(1) 风格）。
     */
    @Test
    void fanoutThreeChildrenInterleavedTerminalsRecoverParentExactlyOnce() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档并给出综合结论", "w-fanout-1");

        // 同一批次语义下的 3 个委派（执行器逐个调用受理路径；此处按同一纪律刷新父视图）
        AgentRunView child1 = admitDelegation(fixture, parent, "研究验收标准与测试覆盖范围");
        parent = repository.findRun(fixture.project(), parent.id()).orElseThrow();
        AgentRunView child2 = admitDelegation(fixture, parent, "研究项目依赖关系与外部接口");
        parent = repository.findRun(fixture.project(), parent.id()).orElseThrow();
        AgentRunView child3 = admitDelegation(fixture, parent, "研究里程碑计划与时间线风险");
        parent = repository.findRun(fixture.project(), parent.id()).orElseThrow();

        // 受理语义：父运行保持 RUNNING 等待（租约立即释放），pending/used 各 +3
        assertThat(parentStatus(parent.id())).isEqualTo("RUNNING");
        assertThat(pending(parent.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT children_used FROM agent_run WHERE id=?",
                Integer.class, parent.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT lease_expires_at <= now() FROM agent_run WHERE id=?",
                Boolean.class, parent.id())).isTrue();

        // 等待态父运行不被 claimNext 误领：连续领取只返回 3 个子运行（同时把它们领取进 RUNNING）
        for (int i = 0; i < 3; i++) {
            var claimed = repository.claimNext("w-fanout-1-w", Duration.ofMinutes(6)).orElseThrow();
            assertThat(claimed.id())
                    .as("等待子运行期间父运行不可被领取")
                    .isIn(child1.id(), child2.id(), child3.id())
                    .isNotEqualTo(parent.id());
        }

        // 子 1：成功终态 → pending 3→2，父保持等待态（不提前恢复）
        repository.recordFinal(repository.findRun(fixture.project(), child1.id()).orElseThrow(),
                "{\"summary\":\"验收标准已核实\",\"findings\":[\"全部测试通过为验收前提\"]}",
                List.of());
        assertThat(pending(parent.id())).isEqualTo(2);
        assertThat(parentStatus(parent.id())).as("仍有在途子运行，父保持等待态").isEqualTo("RUNNING");

        // 子 2：最终失败 → pending 2→1
        repository.recordFailure(repository.findRun(fixture.project(), child2.id()).orElseThrow(),
                "MODEL_FAILED", false);
        assertThat(pending(parent.id())).isEqualTo(1);
        assertThat(parentStatus(parent.id())).isEqualTo("RUNNING");

        // 子 3：取消 → pending 1→0，父重新排队
        repository.recordCanceled(repository.findRun(fixture.project(), child3.id()).orElseThrow());
        assertThat(pending(parent.id())).isZero();
        assertThat(parentStatus(parent.id())).as("全部子终态后父恢复排队").isEqualTo("QUEUED");

        // DELEGATION_COMPLETED step 每子恰好一条，状态如实
        var payloads = completedPayloads(parent.id());
        assertThat(payloads).hasSize(3);
        assertThat(payloads).extracting(p -> p.path("status").asText())
                .containsExactlyInAnyOrder("SUCCEEDED", "FAILED", "CANCELED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE run_id=? AND type='DELEGATION_COMPLETED'",
                Integer.class, parent.id())).isEqualTo(3);

        // 恢复后的父运行可被领取
        var parentClaimed = repository.claimNext("w-fanout-1-p", Duration.ofMinutes(6)).orElseThrow();
        assertThat(parentClaimed.id()).isEqualTo(parent.id());
    }

    /**
     * 部分失败（§3.1）：fan-out 中部分子失败不整体失败；成功子走结构化回传
     * （findings/summary/evidenceDigest 限幅与清洗），失败子按旧格式降级并保留错误码；
     * 父注入块按"成功列表 + 失败列表（含失败原因）"分组。
     */
    @Test
    void fanoutPartialFailureProducesGroupedStructuredCollection() throws Exception {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档并给出综合结论", "w-fanout-2");

        AgentRunView child1 = admitDelegation(fixture, parent, "研究验收标准的量化指标");
        parent = repository.findRun(fixture.project(), parent.id()).orElseThrow();
        AgentRunView child2 = admitDelegation(fixture, parent, "研究部署环境的依赖清单");
        parent = repository.findRun(fixture.project(), parent.id()).orElseThrow();

        // 子 1：结构化回传（含超限字段，验证限幅）
        claimRun(fixture, child1.id(), "w-fanout-2-c1");
        String oversizedFinding = "超长发现".repeat(200); // 800 字符 > 500 上限
        String oversizedExcerpt = "原文摘录".repeat(300); // 1200 字符 > 800 上限
        var structuredJson = json.createObjectNode()
                .put("summary", "结论概述。".repeat(400)) // 2000 字符，恰在上限
                .put("citations", "S1");
        var findings = structuredJson.putArray("findings");
        for (int i = 0; i < 12; i++) findings.add(oversizedFinding + "-" + i); // 12 条 > 10 上限
        var openQuestions = structuredJson.putArray("openQuestions");
        for (int i = 0; i < 6; i++) openQuestions.add("待决问题-" + i); // 6 条 > 5 上限
        var evidence = structuredJson.putArray("evidenceDigest");
        for (int i = 0; i < 9; i++) evidence.add(oversizedExcerpt + "-" + i); // 9 条 > 8 上限
        repository.recordFinal(repository.findRun(fixture.project(), child1.id()).orElseThrow(),
                json.writeValueAsString(structuredJson), List.of());

        // 子 2：最终失败（旧格式内容）
        claimRun(fixture, child2.id(), "w-fanout-2-c2");
        repository.recordFailure(repository.findRun(fixture.project(), child2.id()).orElseThrow(),
                "MODEL_PROVIDER_ERROR", false);

        assertThat(pending(parent.id())).isZero();
        assertThat(parentStatus(parent.id())).isEqualTo("QUEUED");

        var payloads = completedPayloads(parent.id());
        assertThat(payloads).hasSize(2);
        var success = payloads.stream()
                .filter(p -> "SUCCEEDED".equals(p.path("status").asText())).findFirst().orElseThrow();
        var failure = payloads.stream()
                .filter(p -> "FAILED".equals(p.path("status").asText())).findFirst().orElseThrow();

        // 结构化字段限幅：summary ≤2000、findings ≤10 条且每条 ≤500、
        // openQuestions ≤5、evidenceDigest ≤8 条且每条 ≤800；content 兼容字段=summary
        assertThat(success.path("summary").asText().length()).isLessThanOrEqualTo(2000);
        assertThat(success.path("findings").isArray()).isTrue();
        assertThat(success.path("findings").size()).isLessThanOrEqualTo(10);
        success.path("findings").forEach(f -> assertThat(f.asText().length()).isLessThanOrEqualTo(500));
        assertThat(success.path("openQuestions").size()).isLessThanOrEqualTo(5);
        assertThat(success.path("evidenceDigest").size()).isLessThanOrEqualTo(8);
        success.path("evidenceDigest").forEach(e -> assertThat(e.asText().length()).isLessThanOrEqualTo(800));
        assertThat(success.path("content").asText()).isEqualTo(success.path("summary").asText());
        // evidenceDigest 落库前过 sanitizer：载荷内不出现内部字段样式的键
        assertThat(success.toString()).doesNotContain("stackTrace");

        // 失败子按旧格式降级：content 为错误码占位，无 findings 字段
        assertThat(failure.path("content").asText()).isEqualTo("MODEL_PROVIDER_ERROR");
        assertThat(failure.has("findings")).isFalse();

        // 父注入块分组（渲染器直接断言；协调器注入走同一实现）
        String block = DelegatedResearchPromptRenderer.render(payloads.stream()
                .map(p -> new DelegatedResearchPromptRenderer.ChildResult(
                        UUID.fromString(p.path("childRunId").asText()), p))
                .toList());
        assertThat(block).contains("<CHILD_RESEARCH status=\"SUCCEEDED\"");
        assertThat(block).contains("<CHILD_RESEARCH_FAILURE status=\"FAILED\"");
        assertThat(block).contains("子运行执行失败");
        assertThat(block).contains("发现 1：");
        assertThat(block).contains("CHILD_RESEARCH_COVERAGE");
        // 失败分组带重试约束提示（台账判重交互的说明）
        assertThat(block).contains("台账判重");
    }

    /**
     * 计数恢复（§8）：父运行租约被接管时按存活子运行重算 children_pending——
     * pending 漂移（子实际已终态但计数未回收）在 claim 时自愈为 0。
     */
    @Test
    void childrenPendingRecomputedOnParentTakeover() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档", "w-fanout-3");
        AgentRunView child = admitDelegation(fixture, parent, "研究验收标准的证据链");

        // 模拟计数漂移：子运行已终态但 pending 未回收（手工绕过 resumeParent 的场景）
        jdbc.update("UPDATE agent_run SET status='SUCCEEDED',finished_at=now() WHERE id=?", child.id());
        jdbc.update("""
                UPDATE agent_run SET children_pending=3,
                  lease_expires_at=now()-interval '1 minute' WHERE id=?
                """, parent.id());

        // 接管：pending>0 但无存活子运行 → 可领取；领取时按存活子重算 pending
        var claimed = repository.claimNext("w-fanout-3-worker", Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        assertThat(pending(parent.id()))
                .as("接管时按存活子运行数（0）重算 children_pending")
                .isZero();
    }

    /**
     * 唤醒事件（§3.4）：只在子终态事务 afterCommit 后发布（提交前不可见），
     * 携带 parentRunId；重复收口（恢复重放）不重复发布、不重复恢复。
     */
    @Test
    void wakeEventPublishedOnlyAfterCommitAndNotDuplicated() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档", "w-fanout-4");
        AgentRunView child = admitDelegation(fixture, parent, "研究验收标准与评审记录");
        // 子运行领取进入 RUNNING（终态写入的状态前置条件）
        claimRun(fixture, child.id(), "w-fanout-4-child");

        var published = new CopyOnWriteArrayList<AgentDelegationWakeEvent>();
        recorder.setWakePublisher(event ->
                published.add((AgentDelegationWakeEvent) event));

        var txManager = new DataSourceTransactionManager(dataSource);
        var template = new TransactionTemplate(txManager);
        template.executeWithoutResult(status -> {
            repository.recordFinal(repository.findRun(fixture.project(), child.id()).orElseThrow(),
                    "研究发现全文", List.of());
            // 事务提交前（afterCommit 未触发）：无唤醒事件
            assertThat(published).as("afterCommit 之前不得发布唤醒事件").isEmpty();
        });
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().parentRunId()).isEqualTo(parent.id());
        assertThat(parentStatus(parent.id())).isEqualTo("QUEUED");

        // 恢复重放（重复收口）：把子运行回置到 RUNNING 并重放 recordFinal——
        // resumeParent 的幂等键（DELEGATION_COMPLETED step）命中，no-op：
        // 不重复发布唤醒、不重复恢复、不产生第二条回收记录
        jdbc.update("""
                UPDATE agent_run SET status='RUNNING',finished_at=NULL,version=version-1
                WHERE id=?
                """, child.id());
        repository.recordFinal(repository.findRun(fixture.project(), child.id()).orElseThrow(),
                "研究发现全文", List.of());
        assertThat(published).as("重复收口不得再次发布唤醒").hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE run_id=? AND type='DELEGATION_COMPLETED'",
                Integer.class, parent.id())).isEqualTo(1);
        assertThat(pending(parent.id())).isZero();
    }

    /** 部分失败不整体失败（受理面）：fan-out 中一个委派被台账判重拒绝时，已受理的兄弟委派保持等待态。 */
    @Test
    void partialRejectionKeepsParentWaitingWhileSiblingsInFlight() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档", "w-fanout-5");
        String objective = "研究验收标准与测试覆盖范围";
        AgentRunView child1 = admitDelegation(fixture, parent, objective);
        parent = repository.findRun(fixture.project(), parent.id()).orElseThrow();

        // 同 objective 的第二个委派（新 invocation）：台账判重拒绝（前后空白变体同哈希），
        // 且不影响在途兄弟委派
        AgentRunView waitingParent = parent;
        var thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                repository.documentResearchDelegationResult(
                        waitingParent, UUID.randomUUID().toString(), "  研究验收标准与测试覆盖范围  "));
        assertThat(thrown).isInstanceOf(AgentDelegationNotAdmittedException.class);
        assertThat(((AgentDelegationNotAdmittedException) thrown).reasonCode())
                .isEqualTo("AGENT_DELEGATION_DUPLICATE_OBJECTIVE");

        // 在途兄弟委派不受影响：pending=1，父保持等待态，且不被领取（领取只命中子运行）
        assertThat(pending(parent.id())).isEqualTo(1);
        assertThat(parentStatus(parent.id())).isEqualTo("RUNNING");
        assertThat(repository.childRuns(fixture.project(), parent.id())).hasSize(1);
        assertThat(repository.childRuns(fixture.project(), parent.id()).getFirst().id())
                .isEqualTo(child1.id());
        var claimed = repository.claimNext("w-fanout-5-w", Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).as("等待态父运行不被领取").isNotEqualTo(parent.id());
    }

    /** AgentRunStatus 断言辅助：父运行终态前 fan-out 组合下 pending 单调回落且父不提前终态。 */
    @Test
    void fanoutAdmissionConsumesToolQuotaAndTracksPendingPerChild() {
        Fixture fixture = fixture();
        AgentRunView parent = claimedParent(fixture, "研究项目文档", "w-fanout-6");
        AgentRunView child1 = admitDelegation(fixture, parent, "研究目标一");
        parent = repository.findRun(fixture.project(), parent.id()).orElseThrow();
        AgentRunView child2 = admitDelegation(fixture, parent, "研究目标二");
        assertThat(child1.depth()).isEqualTo(1);
        assertThat(child2.depth()).isEqualTo(1);
        // 委派自身消耗工具配额（R8 既有语义，fan-out 下逐委派计数）
        assertThat(jdbc.queryForObject("SELECT tool_calls_used FROM agent_run WHERE id=?",
                Integer.class, parent.id())).isEqualTo(2);
        assertThat(repository.findRun(fixture.project(), parent.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.RUNNING);
    }
}
