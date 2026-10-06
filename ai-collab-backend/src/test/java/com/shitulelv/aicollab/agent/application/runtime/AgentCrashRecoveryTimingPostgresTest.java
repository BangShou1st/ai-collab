package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.flywaydb.core.Flyway;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R1 崩溃接管计时回归（真实 PostgreSQL + 受控时间构造，不等待真实租约）。
 *
 * <p>故障注入方式：直接构造 agent_run 的 claim_started_at / lease_expires_at 数据库状态，
 * 等价于"旧 worker 执行了一小段后进程退出，其租约已过期"，再调用真实的 claimNext 接管。
 * 不需要 sleep，也不引入测试专用执行引擎。</p>
 *
 * <p>计时语义（本轮确认）：active_elapsed_ms 只累计"已确认执行时长"——
 * 区间上界取 min(租约到期, now, last_progress_at)，下界取 claim_started_at；
 * claim 内没有确认进度时累计 0。租约有效期是等待上界，不是执行上界。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentCrashRecoveryTimingPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static AgentRepository repository;
    static AgentRunEventRecorder recorder;

    private AgentContextAssembler assembler;
    private AgentRuntimeCoordinator coordinator;
    private AgentRecoveryJob recovery;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        recorder = new AgentRunEventRecorder(jdbc, json);
        repository = new AgentRepository(jdbc, json, recorder);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM agent_usage_settlement");
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
        recovery = new AgentRecoveryJob(repository);
        assembler = mock(AgentContextAssembler.class);
        coordinator = coordinator();
    }

    /**
     * 1. 少量已执行时间后退出、租约过期再接管：离线等待不累计，接管后的剩余时长足够继续推进。
     *
     * <p>锁定原问题：按旧实现（累加到 lease_expires_at）该运行会立刻得到
     * active_elapsed_ms ≈ 6 分钟 > 5 分钟运行上限，接管即 budgetExceeded。</p>
     */
    @Test
    void expiredLeaseWaitIsNotCountedAsExecutionAndRecoveryStillHasBudget() {
        Fixture fixture = fixture("检查这周任务是否影响交付");
        var run = runningClaimed(fixture);
        AgentRunView active = repository.findRun(fixture.project(), run.id()).orElseThrow();
        repository.recordModelTurn(active,
                new ModelTurnResult("先确认任务分布。", List.of(), ModelFinishReason.STOP,
                        null, "OPENAI_COMPATIBLE", "model-a", 500L));
        // 受控构造"旧 worker 执行 10 秒后退出"：claim 起点在 10 秒前，最后一次确认进度就是现在
        jdbc.update("UPDATE agent_run SET claim_started_at=now()-interval '10 seconds', last_progress_at=now() WHERE id=?",
                run.id());
        expireLeaseSixMinutesAgo(run.id());

        assertThat(elapsed(fixture.project(), run.id())).isBetween(10_000L, 10_500L);
        var takenOver = recovery.claim("worker-b", Duration.ofMinutes(6)).orElseThrow();

        // 旧实现在这里会累计约 360000ms（一直记到租约到期）；本轮只保留已确认的 10 秒
        assertThat(elapsed(fixture.project(), run.id())).isBetween(10_000L, 10_500L);
        assertThat(takenOver.id()).isEqualTo(run.id());
        // 接管后的剩余运行时长仍足够一次推进（>0），不会因等待租约直接到限
        assertThat(300_000L - elapsed(fixture.project(), run.id())).isGreaterThan(0L);
    }

    /**
     * 2. 多次接管不重复累计、不清除既有已确认时长。
     */
    @Test
    void repeatedTakeoversCountEachConfirmedSegmentExactlyOnce() {
        Fixture fixture = fixture("多次接管计时");
        var run = queuedRun(fixture);

        var first = recovery.claim("worker-1", Duration.ofMinutes(6)).orElseThrow();
        assertThat(first.id()).isEqualTo(run.id());
        // 第一次确认结果：既有 10 秒已确认时长 + 本次 claim 内确认的 10 秒
        jdbc.update("UPDATE agent_run SET active_elapsed_ms=10000,"
                + " claim_started_at=now()-interval '20 seconds', last_progress_at=now()-interval '10 seconds' WHERE id=?",
                run.id());
        forceLeaseExpiredAt(run.id(), OffsetDateTime.now().minusSeconds(5));

        recovery.claim("worker-2", Duration.ofMinutes(6)).orElseThrow();
        assertThat(elapsed(fixture.project(), run.id())).isBetween(20_000L, 20_500L);

        // 第二次接管：本次 claim 尚无确认进度 → 不累计、也不清零既有值
        forceLeaseExpiredAt(run.id(), OffsetDateTime.now().minusSeconds(5));
        recovery.claim("worker-3", Duration.ofMinutes(6)).orElseThrow();
        assertThat(elapsed(fixture.project(), run.id())).isBetween(20_000L, 20_500L);

        // 确认过进度的接管之后再次接管：只追加新确认的那一段（2 秒）
        jdbc.update("UPDATE agent_run SET claim_started_at=now()-interval '9 seconds',"
                + " last_progress_at=now()-interval '7 seconds' WHERE id=?", run.id());
        forceLeaseExpiredAt(run.id(), OffsetDateTime.now().minusSeconds(5));
        recovery.claim("worker-4", Duration.ofMinutes(6)).orElseThrow();
        long after = elapsed(fixture.project(), run.id());
        assertThat(after).isGreaterThanOrEqualTo(22_000L).isLessThan(23_000L);
    }

    /**
     * 3. 正常轮次结束与自动重试的计时一致：只累计真实执行区间，等待时间不累计。
     */
    @Test
    void normalRoundEndAndRetryWaitUseTheSameAccumulation() {
        Fixture fixture = fixture("正常计时");
        var run = queuedRun(fixture);
        recovery.claim("worker-1", Duration.ofMinutes(6)).orElseThrow();
        AgentRunView active = repository.findRun(fixture.project(), run.id()).orElseThrow();

        // 正常轮次：模型响应已落库，随后判定为可重试失败（自动重试路径）
        active = repository.recordModelTurn(active,
                new ModelTurnResult("稍后重试。", List.of(), ModelFinishReason.STOP,
                        null, "OPENAI_COMPATIBLE", "model-a", 800L));
        repository.recordFailure(active, "AI_PROVIDER_ERROR", true);

        long confirmed = elapsed(fixture.project(), run.id());
        // 6 分钟租约窗口不得被冒充为已执行时长；这里只有领取到模型轮次之间的极短真实执行区间
        assertThat(confirmed).isGreaterThanOrEqualTo(0L).isLessThan(5_000L);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, run.id()))
                .isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject(
                "SELECT retry_after IS NOT NULL FROM agent_run WHERE id=?", Boolean.class, run.id()))
                .isTrue();

        // 自动重试等待窗口（retry_after 之后）被领取：等待时间不进入累计值
        jdbc.update("UPDATE agent_run SET retry_after=now()-interval '1 second' WHERE id=?", run.id());
        long beforeRetry = elapsed(fixture.project(), run.id());
        recovery.claim("worker-2", Duration.ofMinutes(6)).orElseThrow();
        // 重试等待不进入累计值：领取本身不追加已确认时长（claim 内尚无进度）
        assertThat(elapsed(fixture.project(), run.id())).isBetween(beforeRetry, beforeRetry + 1_000L);
    }

    /**
     * 4. 已真实耗尽时长的运行仍然正确到限：修复不是靠清零或调大限额。
     */
    @Test
    void runThatTrulyExhaustedItsConfirmedDurationStillEndsAtTheLimit() {
        Fixture fixture = fixture("真实到限");
        var run = queuedRun(fixture);
        recovery.claim("worker-1", Duration.ofMinutes(6)).orElseThrow();

        // 已确认执行 300 秒：既有累计 290 秒 + 本次 claim 内确认的 10 秒（略小于整 10 秒，仍必然到限）
        jdbc.update("UPDATE agent_run SET active_elapsed_ms=290000,"
                + " claim_started_at=now()-interval '10 seconds', last_progress_at=now() WHERE id=?", run.id());
        forceLeaseExpiredAt(run.id(), OffsetDateTime.now().minusSeconds(5));
        recovery.claim("worker-2", Duration.ofMinutes(6)).orElseThrow();
        assertThat(elapsed(fixture.project(), run.id())).isGreaterThanOrEqualTo(299_990L);

        AgentRunView claimed = repository.findRun(fixture.project(), run.id()).orElseThrow();
        when(assembler.assemble(any(), any(), any())).thenReturn(context(claimed));
        AgentWorkerOutcome outcome = coordinator.advance(claimed);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_BUDGET_EXCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, run.id()))
                .isEqualTo("BUDGET_EXCEEDED");
    }

    /**
     * 5. 新 worker 接管后，旧 claim 的时长更新与业务提交都不能污染新状态。
     */
    @Test
    void staleWorkerCannotContaminateTheNewClaim() {
        Fixture fixture = fixture("旧 worker 隔离");
        var run = queuedRun(fixture);
        var oldClaim = recovery.claim("worker-old", Duration.ofMinutes(6)).orElseThrow();
        AgentRunView active = repository.findRun(fixture.project(), run.id()).orElseThrow();
        repository.recordModelTurn(active,
                new ModelTurnResult("旧 worker 的响应", List.of(), ModelFinishReason.STOP,
                        null, "OPENAI_COMPATIBLE", "model-a", 100L));

        forceLeaseExpiredAt(run.id(), OffsetDateTime.now().minusSeconds(5));
        var newClaim = recovery.claim("worker-new", Duration.ofMinutes(6)).orElseThrow();
        assertThat(newClaim.version()).isGreaterThan(oldClaim.version());
        long persistedElapsedBefore = jdbc.queryForObject(
                "SELECT active_elapsed_ms FROM agent_run WHERE id=?", Long.class, run.id());

        // 旧 worker 在自己已失效的 claim 版本内继续写模型轮次：必须被租约拦截
        AgentRunView staleView = repository.findRun(fixture.project(), run.id()).orElseThrow();
        try (var scope = new com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope(oldClaim.version())) {
            assertThatThrownBy(() -> repository.recordModelTurn(staleView,
                    new ModelTurnResult("旧 worker 迟到写入", List.of(), ModelFinishReason.STOP,
                            null, "OPENAI_COMPATIBLE", "model-a", 100L)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lease");
        }
        // 时长与业务状态都保持新 claim 的现场：旧 worker 没有推进任何进度锚点。
        // 比较持久化累计值而非 activeElapsedMillis 的实时和——后者含当前 claim 的
        // now()-claim_started_at 活值，两次读取之间墙钟必然前进，不能作为断言依据
        long persistedElapsedAfter = jdbc.queryForObject(
                "SELECT active_elapsed_ms FROM agent_run WHERE id=?", Long.class, run.id());
        assertThat(persistedElapsedAfter).isEqualTo(persistedElapsedBefore);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, run.id()))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE run_id=? AND type='MODEL_TURN'", Integer.class, run.id()))
                .isEqualTo(1);

        // 旧 worker 的失败提交同样不能改状态
        try (var scope = new com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope(oldClaim.version())) {
            assertThatThrownBy(() -> repository.recordFailure(staleView, "AI_PROVIDER_ERROR", false))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, run.id()))
                .isEqualTo("RUNNING");

        // 新 worker 自己的提交照常生效
        try (var scope = new com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope(newClaim.version())) {
            repository.recordFailure(staleView, "AI_PROVIDER_ERROR", false);
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, run.id()))
                .isEqualTo("FAILED");
    }

    // ========== 受控时间构造与测试装配 ==========

    /** 已确认执行时长的可读视图：active_elapsed_ms + 当前 claim 的进行中区间。 */
    private long elapsed(UUID projectId, UUID runId) {
        return repository.activeElapsedMillis(new AgentRunView(
                runId, null, projectId, null, null, null, 0, null, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, false, false, false, 0,
                null, null, null, null, 0, null, null));
    }

    /** 构造"旧 worker 已退出、租约已过期"的数据库状态，不等待真实租约。 */
    private void expireLeaseSixMinutesAgo(UUID runId) {
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '30 seconds' WHERE id=?", runId);
    }

    private void forceLeaseExpiredAt(UUID runId, OffsetDateTime at) {
        jdbc.update("UPDATE agent_run SET lease_expires_at=? WHERE id=?", at, runId);
    }

    private AgentRuntimeCoordinator coordinator() {
        var loopGuard = new AgentLoopGuard();
        var approvals = mock(AgentApprovalService.class);
        var modelExecutor = mock(RoutingAgentModelExecutor.class);
        var sanitizer = new AgentToolResultSanitizer(json);
        var tools = new AgentToolRegistry(List.of());
        var cancellation = new AgentCancellationService(repository);
        var events = mock(AgentEventService.class);
        var registry = new AgentSkillRegistry();
        var planService = new AgentPlanService(repository, json);
        return new AgentRuntimeCoordinator(
                repository, assembler, registry, planService, tools, cancellation, loopGuard,
                approvals, modelExecutor, sanitizer, new AgentConvergencePolicy(), json, events,
                mock(AgentMemoryService.class));
    }

    private com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext context(AgentRunView run) {
        return new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(), "SUPERVISOR", false,
                AgentPageContext.empty(), AgentRuntimeLimits.forSkill(run.skillCode()), 0, List.of());
    }

    private AgentRunView queuedRun(Fixture fixture) {
        var session = repository.createSession(fixture.project(), fixture.user(), "崩溃恢复计时");
        var run = repository.createRun(fixture.project(), session.id(), fixture.user(),
                fixture.goal(), false, "ITERATION_PLANNING", null);
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    /** 领取后的 RUNNING 运行（claim_started_at 为领取时刻）。 */
    private ClaimedAgentRun runningClaimed(Fixture fixture) {
        var run = queuedRun(fixture);
        return recovery.claim("worker-a", Duration.ofMinutes(6)).orElseThrow();
    }

    private record Fixture(UUID user, UUID project, String goal) {
    }

    private Fixture fixture(String goal) {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "timing-" + user.toString().substring(0, 8), "test-only-hash", "Timing");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "崩溃恢复计时验收", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        return new Fixture(user, project, goal);
    }
}
