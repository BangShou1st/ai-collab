package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope;
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
    static org.springframework.transaction.support.TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        recorder = new AgentRunEventRecorder(jdbc, json);
        repository = new AgentRepository(jdbc, json, recorder);
        transactions = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
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

    private static AgentRunEventRecorder.UsageSettlement provider(int in, int out, long latency) {
        return new AgentRunEventRecorder.UsageSettlement(in, out,
                AgentRunEventRecorder.UsageSettlement.PROVIDER,
                AgentRunEventRecorder.UsageSettlement.PROVIDER, latency);
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
                provider(661, 1136, 1000L), null);
        // 重复回调（同 attemptId）不得重复扣费
        repository.completeSummaryAttempt(attemptId, "COMMITTED", "space-bunny-free",
                provider(661, 1136, 1000L), null);

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
        repository.completeSummaryAttempt(attemptId, "FAILED", "unknown",
                AgentRunEventRecorder.UsageSettlement.unknown(), null);
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

        // 模型调用已发生但运行随后被取消：用量必须如实入账。
        // 调用身份在请求发出前由 beginModelCall 落库（每次实际出站请求独立）。
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(REAL_INPUT, 700, 800L))).isTrue();
        recorder.recordCanceled(run);

        AgentRunView canceled = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(canceled.status()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(canceled.inputTokensActual()).isEqualTo(REAL_INPUT);
        assertThat(canceled.outputTokensActual()).isEqualTo(700);
        assertThat(canceled.inputTokensUsed()).isEqualTo(MAX_INPUT);
        // 账本行唯一，来源为提供商上报
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_usage_settlement WHERE run_id=? AND call_id=?",
                Integer.class, run.id(), callId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT usage_basis FROM agent_usage_settlement WHERE run_id=? AND call_id=?",
                String.class, run.id(), callId)).isEqualTo("PROVIDER");
    }

    /** 恢复/取消竞争下重复补记同一调用：调用身份幂等，只能入账一次。 */
    @Test
    void repeatedOrphanSettlementWithSameCallIdentityBooksExactlyOnce() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "重复补记幂等");
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");

        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(REAL_INPUT, 700, 100L))).isTrue();
        // 恢复后的重复补记、取消路径的补记：同一 callId 全部被唯一约束拦下
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(REAL_INPUT, 700, 100L))).isFalse();
        recorder.recordCanceled(run);
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(REAL_INPUT, 700, 100L))).isFalse();

        AgentRunView canceled = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(canceled.inputTokensActual()).isEqualTo(REAL_INPUT);
        assertThat(canceled.outputTokensActual()).isEqualTo(700);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_usage_settlement WHERE run_id=?",
                Integer.class, run.id())).isEqualTo(1);
        // 步骤账本与结算账本可对账：actual = 步骤合计 + 孤儿结算合计
        Integer stepInput = jdbc.queryForObject(
                "SELECT COALESCE(sum(prompt_tokens),0) FROM agent_step WHERE run_id=?",
                Integer.class, run.id());
        Long settledInput = jdbc.queryForObject(
                "SELECT COALESCE(sum(input_tokens_actual),0) FROM agent_usage_settlement WHERE run_id=?",
                Long.class, run.id());
        assertThat(canceled.inputTokensActual()).isEqualTo(stepInput + settledInput);
    }

    /**
     * 最后一次取消检查之后、落库之前取消：recordModelTurn 的租约校验抛出取消、
     * 未推进任何总额；已返回响应的用量按调用身份补结算，不得随取消丢失。
     */
    @Test
    void cancelBetweenLastCheckAndBookingIsSettledByCallIdentity() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "落库前取消");
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");
        int claimVersion = jdbc.queryForObject(
                "SELECT claim_version FROM agent_run WHERE id=?", Integer.class, run.id());

        // 最后一次取消检查之后：取消标记已置位（保持 RUNNING，等待 worker 自行终态）
        repository.requestCancel(fixture.project(), run.id());

        // 落库时租约校验发现取消：抛出且不推进任何总额
        try (var scope = new AgentLeaseScope(claimVersion)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.recordModelTurn(run,
                            turnWith(new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(REAL_INPUT, 700), "已返回的响应")))
                    .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);
        }
        assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().inputTokensActual()).isZero();

        // 按调用身份补结算：费用不丢；随后取消终态如实保留已发生用量
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(REAL_INPUT, 700, 100L))).isTrue();
        recorder.recordCanceled(run);

        AgentRunView canceled = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(canceled.status()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(canceled.inputTokensActual()).isEqualTo(REAL_INPUT);
        assertThat(canceled.outputTokensActual()).isEqualTo(700);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_usage_settlement WHERE run_id=? AND input_tokens_actual IS NOT NULL",
                Integer.class, run.id())).isEqualTo(1);
    }

    /** 相同内容再次真实请求提供商是新的出站请求：新的调用身份，如实再次入账。 */
    @Test
    void genuineSecondRequestGetsItsOwnIdentityAndBooksAgain() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "重复真实请求");

        String first = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), first,
                "MODEL_TURN", provider(REAL_INPUT, 700, 100L))).isTrue();
        String second = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");
        assertThat(second).isNotEqualTo(first);
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), second,
                "MODEL_TURN", provider(100, 50, 60L))).isTrue();

        AgentRunView updated = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(updated.inputTokensActual()).isEqualTo(REAL_INPUT + 100);
        assertThat(updated.outputTokensActual()).isEqualTo(750);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT call_id) FROM agent_usage_settlement WHERE run_id=? AND input_tokens_actual IS NOT NULL",
                Integer.class, run.id())).isEqualTo(2);
        // 同一请求的重复结算仍被身份去重
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), first,
                "MODEL_TURN", provider(REAL_INPUT, 700, 100L))).isFalse();
        assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().inputTokensActual())
                .isEqualTo(REAL_INPUT + 100);
    }

    /** 正常记账（原子）后重复补结算：同一身份不再入账，运行总额不变。 */
    @Test
    void normalAtomicBookingThenRepeatedOrphanSettleKeepsTotalsUnchanged() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "原子正常记账");
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");

        transactions.executeWithoutResult(tx -> repository.recordModelTurnWithSettlement(run,
                turnWith(new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(REAL_INPUT, 700), "正常响应"),
                callId, "MODEL_TURN", provider(REAL_INPUT, 700, 100L)));

        assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().inputTokensActual())
                .isEqualTo(REAL_INPUT);
        assertThat(jdbc.queryForObject(
                "SELECT input_tokens_actual FROM agent_usage_settlement WHERE run_id=? AND call_id=?",
                Integer.class, run.id(), callId)).isEqualTo(REAL_INPUT);
        // 同一身份重复补结算：被条件更新拒绝，总额不变
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(REAL_INPUT, 700, 100L))).isFalse();
        assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().inputTokensActual())
                .isEqualTo(REAL_INPUT);
    }

    /**
     * 故障注入：正常记账事务中途失败（版本冲突使 recordModelTurn 抛出）→
     * 身份行结算与运行累计共同回滚——身份行仍为未结算、运行总额未变；
     * 之后同身份补结算恰好一次。
     */
    @Test
    void settlementFailureRollsBackIdentityAndRunTotalsTogether() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "注入失败共同回滚");
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");
        // 故障注入：携带过期 version 的运行视图，使 recordModelTurn 的版本 CAS 失败
        AgentRunView stale = new AgentRunView(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                run.parentRunId(), run.role(), run.depth(), run.goal(), run.status(),
                run.maxSteps(), run.maxToolCalls(), run.maxChildren(),
                run.maxInputTokens(), run.maxOutputTokens(),
                run.stepsUsed(), run.toolCallsUsed(), run.childrenUsed(),
                run.inputTokensUsed(), run.outputTokensUsed(), run.inputTokensActual(), run.outputTokensActual(),
                run.tokenUsageEstimated(), run.scheduled(), run.correctionAttempted(),
                run.retryCount(), run.errorCode(), run.planJson(), run.pageContextJson(), run.skillCode(),
                run.version() + 999, run.createdAt(), run.updatedAt());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.executeWithoutResult(tx ->
                repository.recordModelTurnWithSettlement(stale,
                        turnWith(new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(300, 50), "将被回滚"),
                        callId, "MODEL_TURN", provider(300, 50, 1L))))
                .isInstanceOf(IllegalStateException.class);

        // 共同回滚：身份行仍为未结算（NULL），运行总额未变
        assertThat(jdbc.queryForObject(
                "SELECT input_tokens_actual FROM agent_usage_settlement WHERE run_id=? AND call_id=?",
                Integer.class, run.id(), callId)).isNull();
        assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().inputTokensActual()).isZero();
        // 之后同身份补结算恰好一次
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(REAL_INPUT, 700, 1L))).isTrue();
        assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().inputTokensActual())
                .isEqualTo(REAL_INPUT);
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(REAL_INPUT, 700, 1L))).isFalse();
    }

    /** 输出超限的原子记账与同身份补结算互斥：恰好入账一次。 */
    @Test
    void budgetExceededWithSettlementBooksExactlyOnce() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "输出超限原子记账");
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");

        transactions.executeWithoutResult(tx -> repository.recordBudgetExceededWithSettlement(run,
                new ChatCompletionResult("超长输出", "OPENAI_COMPATIBLE", "space-bunny-free", 300, 25000, 10L),
                callId, "MODEL_TURN", provider(300, 25000, 10L)));

        AgentRunView exceeded = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(exceeded.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(exceeded.outputTokensActual()).isEqualTo(25000);
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(300, 25000, 10L))).isFalse();
        assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().outputTokensActual())
                .isEqualTo(25000);
    }

    /** 恢复接管收口：未结算身份行显式转为 0/0 + UNKNOWN 终态，不虚构消耗。 */
    @Test
    void recoveryClosesUnresolvedIdentitiesAsExplicitUnknown() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "恢复收口");
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");

        int closed = repository.closeUnresolvedModelCalls(run.id());

        assertThat(closed).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT usage_basis FROM agent_usage_settlement WHERE run_id=? AND call_id=?",
                String.class, run.id(), callId)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject(
                "SELECT input_tokens_actual FROM agent_usage_settlement WHERE run_id=? AND call_id=?",
                Integer.class, run.id(), callId)).isZero();
        assertThat(repository.findRun(fixture.project(), run.id()).orElseThrow().inputTokensActual()).isZero();
        // 已收口的行不再被补结算占用
        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId,
                "MODEL_TURN", provider(REAL_INPUT, 700, 1L))).isFalse();
    }

    /** 缺失 usage 的孤儿结算：按请求/响应证据估算，basis 显式为 ESTIMATED。 */
    @Test
    void orphanSettlementWithoutProviderUsageUsesEvidenceEstimate() {
        Fixture fixture = fixture();
        AgentRunView run = runningRun(fixture, "孤儿估算");
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");

        assertThat(repository.settleOrphanUsage(fixture.project(), run.id(), callId, "MODEL_TURN",
                AgentRunEventRecorder.UsageSettlement.fromRaw(null, 4000, 300, null))).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT usage_basis FROM agent_usage_settlement WHERE run_id=? AND call_id=?",
                String.class, run.id(), callId)).isEqualTo("ESTIMATED");
        AgentRunView updated = repository.findRun(fixture.project(), run.id()).orElseThrow();
        assertThat(updated.inputTokensActual()).isEqualTo(4000);
        assertThat(updated.outputTokensActual()).isEqualTo(300);
        assertThat(updated.tokenUsageEstimated()).isTrue();
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
