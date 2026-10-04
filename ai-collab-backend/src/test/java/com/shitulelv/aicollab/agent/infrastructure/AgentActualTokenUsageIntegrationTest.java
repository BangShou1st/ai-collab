package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实际用量不被预算上限遮蔽（真实 PostgreSQL）。
 *
 * <p>回归根因：fixed24-final2 第 14 轮真实输入 52,289 > 50,000，运行记录却为 50,000
 * 且 SUCCEEDED。used 列保持预算语义（V22 ck_agent_run_budgets 限制 used <= max），
 * actual 列必须如实累计真实消耗；超额结算后明确终止，不执行该响应中的工具。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentActualTokenUsageIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static AgentRepository repository;
    static AgentRunEventRecorder recorder;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        recorder = new AgentRunEventRecorder(jdbc, json);
        repository = new AgentRepository(jdbc, json, recorder);
    }

    @BeforeEach
    void clearAgentFixtures() {
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
    }

    /** 第 14 轮证据复现：真实输入 52,289 超过 50,000 上限。 */
    private static final int MAX_INPUT = 50000;
    private static final int REAL_INPUT = 52289;

    private record Fixture(UUID user, UUID project) {
    }

    private Fixture fixture() {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "usage-" + user.toString().substring(0, 8), "test-only-hash", "Usage");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "Actual usage", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        return new Fixture(user, project);
    }

    private AgentRunView runningRun(Fixture fixture, String goal) {
        var session = repository.createSession(fixture.project(), fixture.user(), "用量回归");
        var queued = repository.createRun(
                fixture.project(), session.id(), fixture.user(), goal, false, null, null);
        jdbc.update("UPDATE agent_run SET max_input_tokens=?, max_output_tokens=? WHERE id=?",
                MAX_INPUT, 20000, queued.id());
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        return repository.findRun(fixture.project(), queued.id()).orElseThrow();
    }

    private ModelTurnResult turnWith(ModelUsage usage, String content, ModelToolCall... calls) {
        return new ModelTurnResult(content, List.of(calls), ModelFinishReason.STOP, usage,
                "OPENAI_COMPATIBLE", "space-bunny-free", 1200L);
    }

    @Test
    void realInputUsageIsNotShadowedByRunCap() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "固定24轮第14轮复现");

        AgentRunView updated = repository.recordModelTurn(run,
                turnWith(new ModelUsage(REAL_INPUT, 900), "收到资料"));

        assertThat(updated.inputTokensActual()).isEqualTo(REAL_INPUT);
        assertThat(updated.outputTokensActual()).isEqualTo(900);
        assertThat(updated.inputTokensUsed()).isEqualTo(MAX_INPUT);
        assertThat(updated.outputTokensUsed()).isEqualTo(900);
        assertThat(jdbc.queryForObject(
                "SELECT usage_basis FROM agent_step WHERE run_id=? AND type='MODEL_TURN'",
                String.class, run.id())).isEqualTo("PROVIDER");
        assertThat(jdbc.queryForObject(
                "SELECT input_tokens_actual FROM agent_run WHERE id=?", Long.class, run.id()))
                .isEqualTo(REAL_INPUT);
        // 预算剩余单独计算，最低为 0；used 不因超额突破上限（CHECK 保护）
        assertThat(jdbc.queryForObject(
                "SELECT GREATEST(0, max_input_tokens-input_tokens_used) FROM agent_run WHERE id=?",
                Integer.class, run.id())).isZero();
    }

    @Test
    void actualInputOvershootSettlesRealUsageAndStopsWithoutToolExecution() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "超额必须终止");
        AgentRunView afterTurn = repository.recordModelTurn(run, turnWith(
                new ModelUsage(REAL_INPUT, 900), "需要继续查询",
                new ModelToolCall("call-1", "list_tasks",
                        new ObjectMapper().createObjectNode().put("status", "PENDING"))));

        // 输入实际超额：结算真实消耗（已由 recordModelTurn 写入）并明确终止
        recorder.recordBudgetExceeded(afterTurn);

        AgentRunView finished = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(finished.inputTokensActual()).isEqualTo(REAL_INPUT);
        assertThat(finished.outputTokensActual()).isEqualTo(900);
        // 该响应中的工具不得执行
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-1'",
                String.class, run.id())).isEqualTo("SKIPPED");
        // 不记成功：没有最终回答消息
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_message WHERE run_id=? AND role='ASSISTANT'",
                Integer.class, run.id())).isZero();
    }

    @Test
    void repeatedSummarySettlementIsIdempotentAndRecordsActual() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "摘要幂等结算");
        UUID attemptId = repository.beginSummaryAttempt(run);

        repository.completeSummaryAttempt(attemptId, "COMMITTED", "space-bunny-free",
                661, 1136, false, 1000L);
        // 重复回调（同 attemptId）不得重复扣费
        repository.completeSummaryAttempt(attemptId, "COMMITTED", "space-bunny-free",
                661, 1136, false, 1000L);

        AgentRunView updated = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(updated.inputTokensActual()).isEqualTo(661);
        assertThat(updated.outputTokensActual()).isEqualTo(1136);
        assertThat(jdbc.queryForObject(
                "SELECT usage_basis FROM agent_step WHERE id=?", String.class, attemptId))
                .isEqualTo("PROVIDER");
    }

    @Test
    void usageWithoutAnyEvidenceIsMarkedUnknownNeverZeroPrecise() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "未知usage标记");

        // 无提供商 usage，但请求内容存在 → ESTIMATED，且按证据估算而非零
        AgentRunView updated = repository.recordModelTurn(run,
                turnWith(null, "这段内容没有任何usage上报，必须保守估算"));

        assertThat(jdbc.queryForObject(
                "SELECT usage_basis FROM agent_step WHERE run_id=? AND type='MODEL_TURN'",
                String.class, run.id())).isEqualTo("ESTIMATED");
        assertThat(updated.inputTokensActual()).isGreaterThan(0);
        assertThat(updated.tokenUsageEstimated()).isTrue();

        // 连证据都没有 → UNKNOWN
        UUID attemptId = repository.beginSummaryAttempt(updated);
        repository.completeSummaryAttempt(attemptId, "FAILED", "unknown", null, null, true, null);
        assertThat(jdbc.queryForObject(
                "SELECT usage_basis FROM agent_step WHERE id=?", String.class, attemptId))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void failedSettlementRecordsActualAboveCapWithoutViolatingBudgetConstraint() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "失败也要如实记账");

        recorder.recordDecisionFailure(run,
                new ChatCompletionResult("输出很长", "OPENAI_COMPATIBLE", "space-bunny-free",
                        60000, 25000, 1500L),
                "AGENT_INVALID_RESPONSE", "契约外输出");

        AgentRunView failed = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        // actual 如实累计（可高于上限），used 封顶（CHECK 不被破坏）
        assertThat(failed.inputTokensActual()).isEqualTo(60000);
        assertThat(failed.outputTokensActual()).isEqualTo(25000);
        assertThat(failed.inputTokensUsed()).isEqualTo(MAX_INPUT);
        assertThat(failed.outputTokensUsed()).isEqualTo(20000);
    }

    @Test
    void orphanUsageAfterCancellationIsStillSettled() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "取消前结算");

        // 模型调用已发生但运行随后被取消：用量必须如实入账
        repository.settleOrphanModelUsage(fixture.project(), run.id(), REAL_INPUT, 700, false);
        recorder.recordCanceled(run);

        AgentRunView canceled = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(canceled.status()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(canceled.inputTokensActual()).isEqualTo(REAL_INPUT);
        assertThat(canceled.outputTokensActual()).isEqualTo(700);
        assertThat(canceled.inputTokensUsed()).isEqualTo(MAX_INPUT);
    }

    @Test
    void childRunActualUsageAggregatesToParentWithoutShadowing() {
        Fixture fixture = fixture();
        AgentRunView parent = runningRun(fixture, "父运行汇总");
        jdbc.update("UPDATE agent_run SET max_children=1 WHERE id=?", parent.id());
        AgentRunView child = repository.recordDelegation(parent,
                new ChatCompletionResult("委托", "OPENAI_COMPATIBLE", "space-bunny-free", 100, 20, 50L),
                new AgentDecision.Delegate("KNOWLEDGE_RESEARCHER", "子目标"));
        jdbc.update("UPDATE agent_run SET max_input_tokens=?, max_output_tokens=? WHERE id=?",
                MAX_INPUT, 20000, child.id());
        repository.claimNext("worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(1));
        AgentRunView runningChild = repository.findRun(fixture.project(), child.id()).orElseThrow();

        repository.recordModelTurn(runningChild,
                turnWith(new ModelUsage(REAL_INPUT, 900), "子运行消耗巨大"));
        AgentRunView finishedChild = repository.findRun(fixture.project(), child.id()).orElseThrow();
        recorder.recordBudgetExceeded(finishedChild,
                new ChatCompletionResult("超预算", "OPENAI_COMPATIBLE", "space-bunny-free", 0, 0, 5L));

        AgentRunView resumedParent = repository.findRun(fixture.project(), parent.id()).orElseThrow();
        // 父运行 actual 如实汇总子运行真实消耗（含超额部分），used 保持封顶
        assertThat(resumedParent.inputTokensActual()).isGreaterThanOrEqualTo(REAL_INPUT);
        assertThat(resumedParent.inputTokensUsed()).isLessThanOrEqualTo(MAX_INPUT);
    }
}
