package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentRunService;
import com.shitulelv.aicollab.agent.application.AgentWorker;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.api.dto.CreateAgentSessionRequest;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentScenarioRegistry;
import com.shitulelv.aicollab.agent.domain.policy.AgentSessionBudgetPolicy;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentRuntimeMetrics;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.agent.infrastructure.tool.DocumentResearchDelegateAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.KnowledgeSearchAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.ProjectQuestionAgentTool;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.retrieval.ProjectKnowledgeRetrievalTestSupport;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话聚合 token 预算（预算治理 v3，设计文档 §4.1，V83）集成回归（真实 PostgreSQL）：
 * <ul>
 *   <li>EXHAUSTED：模型轮强制无工具收尾，产出部分结果而非硬杀，无工具调用落库；</li>
 *   <li>FINALIZE：仅注入收敛提示，不改工具可见性；</li>
 *   <li>子运行消耗按 session 归账；</li>
 *   <li>并发记账：DB 端 agent_capped_add 单语句封顶，不超顶、不丢单；</li>
 *   <li>存量会话（cap NULL）行为不变；新会话写配置默认上限。</li>
 * </ul>
 * 不依赖真实模型：模型行为由 mock 注入确定性响应。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentSessionAggregateBudgetPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static AgentRunEventRecorder recorder;
    static AgentRepository repository;

    private AgentContextAssembler assembler;
    private AgentEventService events;
    private RoutingAgentModelExecutor modelExecutor;
    private AgentApprovalService approvals;
    private AgentRuntimeMetrics metrics;
    private SimpleMeterRegistry meterRegistry;
    private AgentRuntimeCoordinator coordinator;
    private AgentWorker worker;
    private AgentRecoveryJob recovery;
    private final List<String> modelRequests = new CopyOnWriteArrayList<>();
    /** 每次真实模型请求实际暴露的工具名（EXHAUSTED 必须剥空、FINALIZE 必须保留）。 */
    private final List<List<String>> exposedToolNames = new CopyOnWriteArrayList<>();

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
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM agent_delegation_ledger");
        jdbc.update("DELETE FROM agent_usage_settlement");
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
        assembler = mock(AgentContextAssembler.class);
        events = mock(AgentEventService.class);
        modelExecutor = mock(RoutingAgentModelExecutor.class);
        approvals = mock(AgentApprovalService.class);
        ReflectionTestUtils.setField(recorder, "events", events);
        recovery = new AgentRecoveryJob(repository);
        modelRequests.clear();
        exposedToolNames.clear();
        meterRegistry = new SimpleMeterRegistry();
        metrics = new AgentRuntimeMetrics(meterRegistry);
        when(assembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
            AgentRunView argumentRun = invocation.getArgument(0);
            return argumentRun == null ? null : context(argumentRun);
        });
        when(modelExecutor.resolveRequest(any()))
                .thenReturn(AgentRuntimeBehaviorTest.resolved(false));
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            List<com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage> messages = invocation.getArgument(1);
            modelRequests.add(messages == null ? "(no-messages)" : messages.toString());
            List<com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition> definitions = invocation.getArgument(2);
            exposedToolNames.add(definitions == null ? List.of()
                    : definitions.stream().map(com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition::name).toList());
            return new ModelTurnResult("基于已有信息的部分回答", List.of(), ModelFinishReason.STOP,
                    new ModelUsage(800, 200), "OPENAI_COMPATIBLE", "model-a", 100L);
        });
        coordinator = coordinator();
        worker = new AgentWorker(repository, coordinator, events, json);
        ReflectionTestUtils.setField(worker, "cancellation", new AgentCancellationService(repository));
    }

    private AgentRuntimeCoordinator coordinator() {
        // 与生产同款 16 参装配：注入真实指标（SimpleMeterRegistry）便于断言 counter
        var access = mock(ProjectAccessGuard.class);
        Mockito.lenient().when(access.requireMember(any(), any())).thenReturn(ProjectRole.OWNER);
        var search = mock(DocumentSearchService.class);
        Mockito.lenient().when(search.resolveUserDocumentScope(any(), any()))
                .thenReturn(DocumentSearchService.UserDocumentScope.unrestricted());
        Mockito.lenient().when(search.search(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        var knowledgeTool = new KnowledgeSearchAgentTool(
                ProjectKnowledgeRetrievalTestSupport.legacyBacked(access, search),
                new KnowledgeContextBuilder(), json);
        var questionAlias = new ProjectQuestionAgentTool(knowledgeTool);
        var delegateTool = new DocumentResearchDelegateAgentTool(json);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(knowledgeTool, questionAlias, delegateTool));
        var toolExecutor = new AgentToolCallExecutor(repository, registry,
                new AgentCancellationService(repository), new AgentLoopGuard(), approvals, modelExecutor,
                new AgentToolResultSanitizer(json), json, events, new AgentToolScheduler());
        return new AgentRuntimeCoordinator(
                repository, assembler, new AgentScenarioRegistry(), new AgentPlanService(repository, json),
                registry, new AgentCancellationService(repository), modelExecutor,
                new AgentConvergencePolicy(), json, events,
                new AgentContextProperties(true, 20_000, 4_000, 2_000, java.util.Map.of()),
                AgentBudgetProperties.defaults(), metrics,
                new AgentModelMessageComposer(repository, mock(AgentMemoryService.class), json),
                toolExecutor,
                new AgentContextSummarizer(repository, modelExecutor, json));
    }

    private com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext context(AgentRunView run) {
        return new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(), "OWNER", false,
                AgentPageContext.empty(),
                run.depth() > 0 ? AgentRuntimeLimits.defaults()
                        : AgentRuntimeLimits.forScenario(run.skillCode()),
                run.depth(), List.of());
    }

    private Fixture fixture(Long maxAggregateTokens) {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "sab-" + user.toString().substring(0, 8), "test-only-hash", "SessionBudget");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "会话聚合预算验收", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        var session = maxAggregateTokens == null
                ? repository.createSession(project, user, "会话聚合预算验收")
                : repository.createSession(project, user, "会话聚合预算验收", maxAggregateTokens);
        return new Fixture(user, project, session.id());
    }

    /** 创建并领取一个 v1 兼容父运行（委派受理路径的最小夹具，与委派回归同款）。 */
    private AgentRunView v1RunAndClaim(Fixture fixture, String goal, String workerName) {
        return v1RunAndClaimed(fixture, goal, workerName).run();
    }

    private record ClaimedRun(AgentRunView run, ClaimedAgentRun claimed) {}

    /** 同上，但保留领取凭据：预记账（run version 推进）后仍用同一凭据走 worker 准入。 */
    private ClaimedRun v1RunAndClaimed(Fixture fixture, String goal, String workerName) {
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                goal, false, null, null);
        jdbc.update("""
                UPDATE agent_run SET context_policy_version=1, max_steps=12, max_tool_calls=8,
                  max_input_tokens=50000, max_output_tokens=20000 WHERE id=?
                """, run.id());
        ClaimedAgentRun claimed = recovery.claim(workerName, Duration.ofMinutes(6)).orElseThrow();
        return new ClaimedRun(repository.findRun(fixture.project(), run.id()).orElseThrow(), claimed);
    }

    /** 直接把运行置为 RUNNING（并发记账测试用：不走 claim 排序，三条运行各自独立记账）。 */
    private AgentRunView forceRunning(Fixture fixture, String goal, String workerName) {
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                goal, false, null, null);
        jdbc.update("""
                UPDATE agent_run SET status='RUNNING', started_at=now(), lease_owner=?,
                  lease_expires_at=now()+interval '6 minutes' WHERE id=?
                """, workerName, run.id());
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    /** 纯记账用途的模型轮：带 usage、无工具调用（内容占位，调用方按需标记 batchHandled）。 */
    private ModelTurnResult bookingTurn(int inputTokens, int outputTokens) {
        return new ModelTurnResult("（预算记账轮）", List.of(), ModelFinishReason.STOP,
                new ModelUsage(inputTokens, outputTokens), "OPENAI_COMPATIBLE", "model-a", 5L);
    }

    /** 把运行当前已落库的模型轮标记为已消费（batchHandled），避免干扰 pendingModelTurn 准入。 */
    private void markTurnsBatchHandled(UUID runId) {
        jdbc.update("""
                UPDATE agent_step SET output_json = jsonb_set(output_json, '{batchHandled}', 'true'::jsonb)
                WHERE run_id=? AND type='MODEL_TURN'
                """, runId);
    }

    private long sessionAggregateUsed(UUID sessionId) {
        return jdbc.queryForObject(
                "SELECT aggregate_tokens_used FROM agent_session WHERE id=?", Long.class, sessionId);
    }

    private Long sessionAggregateCap(UUID sessionId) {
        return jdbc.queryForObject(
                "SELECT max_aggregate_tokens FROM agent_session WHERE id=?", Long.class, sessionId);
    }

    private long runActualTokens(UUID runId) {
        var row = jdbc.queryForMap(
                "SELECT input_tokens_actual, output_tokens_actual FROM agent_run WHERE id=?", runId);
        return ((Number) row.get("input_tokens_actual")).longValue()
                + ((Number) row.get("output_tokens_actual")).longValue();
    }

    private double sessionWarningCounter(String level) {
        var counter = meterRegistry.find("agent.budget.session.warning").tag("level", level).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // ========== EXHAUSTED：强制无工具收尾，产出部分结果而非硬杀 ==========

    @Test
    void exhaustedSessionForcesNoToolFinalTurnProducingPartialResult() {
        Fixture fixture = fixture(5_000L);
        var parent = v1RunAndClaimed(fixture, "研究并回答项目风险", "w-exh");
        // 预记账：父运行一轮消耗 5000 → 会话剩余 = 0（cap=5000），EXHAUSTED
        recorder.recordModelTurn(parent.run(), bookingTurn(5_000, 0));
        markTurnsBatchHandled(parent.run().id());

        // 同一领取凭据（epoch = claim_version，预记账只推进 version 不动 claim_version）
        AgentWorkerOutcome outcome = worker.process(parent.claimed());

        // 不硬杀：强制无工具收尾轮产出部分结果，运行正常终态
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_run WHERE id=?",
                String.class, parent.run().id())).isEqualTo("SUCCEEDED");
        // 无工具调用落库（预记账轮与收尾轮都不含工具调用）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_tool_invocation WHERE run_id=?",
                Integer.class, parent.run().id())).isZero();
        // 本轮实际暴露的工具定义被剥空
        assertThat(exposedToolNames).isNotEmpty();
        assertThat(exposedToolNames.getLast()).as("EXHAUSTED 轮工具定义必须剥空")
                .isEmpty();
        // 注入会话级收敛提示（文案与 run 级软阈值不同）
        assertThat(modelRequests.getLast()).contains("会话预算警告").contains("本轮是最后一轮");
        // 最终部分回答落为 ASSISTANT 消息
        var answers = jdbc.queryForList(
                "SELECT content FROM agent_message WHERE session_id=? AND role='ASSISTANT'",
                String.class, fixture.session());
        assertThat(answers).anyMatch(c -> c.contains("基于已有信息的部分回答"));
        // 聚合封顶不超顶（收尾轮的消耗经 capped_add 后仍 == cap）
        assertThat(sessionAggregateUsed(fixture.session())).isEqualTo(5_000L);
        // 指标：EXHAUSTED 恰好计一次
        assertThat(sessionWarningCounter("EXHAUSTED")).isEqualTo(1.0);
    }

    // ========== FINALIZE：仅注入收敛提示，不改工具可见性 ==========

    @Test
    void finalizeSessionInjectsHintWithoutStrippingTools() {
        Fixture fixture = fixture(30_000L);
        var parent = v1RunAndClaimed(fixture, "研究并回答项目风险", "w-fin");
        // 预记账 12000 → 剩余 18000 < 收尾预留 20000 → FINALIZE（未触顶）
        recorder.recordModelTurn(parent.run(), bookingTurn(12_000, 0));
        markTurnsBatchHandled(parent.run().id());

        AgentWorkerOutcome outcome = worker.process(parent.claimed());

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        // FINALIZE 只注入提示：工具定义保留（与 EXHAUSTED 的剥空形成对照）
        assertThat(exposedToolNames.getLast()).as("FINALIZE 轮不得剥离工具定义").isNotEmpty();
        assertThat(modelRequests.getLast()).contains("会话预算提示").contains("即将用尽");
        // 未触顶：聚合如实累计且与 run 级入账一致（12000 预记账 + 主轮 + 可能的辅助
        // 摘要调用，均经记账区归账到会话——聚合 == run 实际入账之和）
        assertThat(sessionAggregateUsed(fixture.session()))
                .isEqualTo(runActualTokens(parent.run().id()))
                .isLessThan(30_000L);
        assertThat(sessionWarningCounter("FINALIZE")).isEqualTo(1.0);
        assertThat(sessionWarningCounter("EXHAUSTED")).isZero();
    }

    // ========== 子运行消耗按 session 归账 ==========

    @Test
    void childRunUsageCountsIntoSessionAggregate() {
        Fixture fixture = fixture(1_000_000L);
        AgentRunView parent = v1RunAndClaim(fixture, "结合需求文档分析项目风险", "w-child-1");
        // 父运行预记账一轮 1500
        recorder.recordModelTurn(parent, bookingTurn(1_000, 500));
        // 真实委派路径创建子运行（同 session、depth=1）
        AgentRunView refreshed = repository.findRun(fixture.project(), parent.id()).orElseThrow();
        var result = repository.documentResearchDelegationResult(
                refreshed, UUID.randomUUID().toString(), "研究项目文档中的验收标准与依赖关系");
        assertThat(result.path("status").asText()).isEqualTo("DELEGATED");
        UUID childId = UUID.fromString(result.path("childRunId").asText());
        assertThat(jdbc.queryForObject(
                "SELECT session_id FROM agent_run WHERE id=?", UUID.class, childId))
                .isEqualTo(fixture.session());

        // 领取子运行并记账一轮 1500：子运行消耗必须计入同一会话聚合
        ClaimedAgentRun childClaimed = null;
        for (int guard = 0; guard < 10; guard++) {
            var claimed = recovery.claim("w-child-2", Duration.ofMinutes(6)).orElseThrow();
            if (claimed.id().equals(childId)) {
                childClaimed = claimed;
                break;
            }
        }
        assertThat(childClaimed).as("子运行未被领取").isNotNull();
        AgentRunView child = repository.findRun(fixture.project(), childId).orElseThrow();
        recorder.recordModelTurn(child, bookingTurn(1_000, 500));

        // 父 + 子各自 1500，聚合 = 3000（按各自 session_id 归账，同一会话）
        assertThat(sessionAggregateUsed(fixture.session())).isEqualTo(3_000L);
    }

    // ========== 并发记账：不超顶、不丢单 ==========

    @Test
    void concurrentBookingUnderCapAccumulatesExactSum() throws Exception {
        Fixture fixture = fixture(200_000L);
        UUID parent = forceRunning(fixture, "父运行记账", "w-c0").id();
        UUID child1 = forceRunning(fixture, "子运行一号记账", "w-c1").id();
        UUID child2 = forceRunning(fixture, "子运行二号记账", "w-c2").id();
        // 父 + 2 子各 5 轮 × 1000 = 15000 < cap 200000：聚合必须等于各 run 实际之和
        runConcurrentBooking(List.of(parent, child1, child2), 5, 1_000);
        long expected = 3 * 5 * 1_000L;
        assertThat(sessionAggregateUsed(fixture.session())).isEqualTo(expected);
        assertThat(expected).isLessThanOrEqualTo(sessionAggregateCap(fixture.session()));
        // 与 run 级 actual 之和一致（不丢单）
        long runSum = runActualTokens(parent) + runActualTokens(child1) + runActualTokens(child2);
        assertThat(runSum).isEqualTo(expected);
    }

    @Test
    void concurrentBookingOverCapCapsAtMaxWithoutLostUpdates() throws Exception {
        Fixture fixture = fixture(50_000L);
        UUID parent = forceRunning(fixture, "父运行记账", "w-c0").id();
        UUID child1 = forceRunning(fixture, "子运行一号记账", "w-c1").id();
        UUID child2 = forceRunning(fixture, "子运行二号记账", "w-c2").id();
        // 父 + 2 子各 20 轮 × 1000 = 60000 > cap 50000：封顶但不丢单
        runConcurrentBooking(List.of(parent, child1, child2), 20, 1_000);
        assertThat(sessionAggregateUsed(fixture.session())).isEqualTo(50_000L);
        // run 级 actual 如实累计 60000（封顶只作用于会话聚合列）
        long runSum = runActualTokens(parent) + runActualTokens(child1) + runActualTokens(child2);
        assertThat(runSum).isEqualTo(60_000L);
    }

    /** 多线程模拟父 + 2 子同时记账：每线程对各自 run 连续 record N 轮，全部成功无异常。 */
    private void runConcurrentBooking(List<UUID> runIds, int turnsPerRun, int tokensPerTurn) throws Exception {
        Map<UUID, AgentRunView> views = new java.util.concurrent.ConcurrentHashMap<>();
        for (UUID runId : runIds) {
            views.put(runId, repository.findRun(fixtureProjectOf(runId), runId).orElseThrow());
        }
        ExecutorService pool = Executors.newFixedThreadPool(runIds.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (UUID runId : runIds) {
                futures.add(pool.submit(() -> {
                    start.await();
                    AgentRunView run = views.get(runId);
                    var turn = bookingTurn(tokensPerTurn / 2, tokensPerTurn - tokensPerTurn / 2);
                    for (int i = 0; i < turnsPerRun; i++) {
                        // 记账返回推进 version 后的运行视图，下一轮以最新版本继续
                        run = recorder.recordModelTurn(run, turn);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS); // 任一线程异常即测试失败（不丢单的前置：全部记账成功）
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID fixtureProjectOf(UUID runId) {
        return jdbc.queryForObject("SELECT project_id FROM agent_run WHERE id=?", UUID.class, runId);
    }

    // ========== 存量会话（cap NULL）行为不变 + 新会话默认上限 ==========

    @Test
    void legacySessionWithNullCapKeepsUnlimitedBehavior() {
        // 3 参历史入口：max_aggregate_tokens 落 NULL（存量语义），聚合只记账不封顶
        Fixture fixture = fixture(null);
        assertThat(sessionAggregateCap(fixture.session())).isNull();
        AgentRunView run = forceRunning(fixture, "存量会话记账", "w-legacy");
        for (int i = 0; i < 3; i++) {
            run = recorder.recordModelTurn(run, bookingTurn(50_000, 0));
        }
        assertThat(sessionAggregateUsed(fixture.session())).isEqualTo(150_000L);
        // 准入视图：cap NULL → 策略 NONE（不限）
        var budget = repository.sessionAggregateBudget(fixture.session());
        assertThat(budget).isNotNull();
        assertThat(budget.maxAggregateTokens()).isNull();
        assertThat(AgentSessionBudgetPolicy.evaluate(budget.aggregateTokensUsed(), budget.maxAggregateTokens()))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
    }

    @Test
    void newSessionWritesConfiguredDefaultCap() {
        // 4 参入口：写入配置默认 2,000,000；0/负数防御性落 NULL（不限）
        Fixture fixture = fixture(AgentBudgetProperties.defaults().sessionAggregateTokenCap());
        assertThat(sessionAggregateCap(fixture.session())).isEqualTo(2_000_000L);
        assertThat(repository.createSession(fixture.project(), fixture.user(), "零上限会话", 0L))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT max_aggregate_tokens FROM agent_session WHERE project_id=? AND title='零上限会话'",
                Long.class, fixture.project())).isNull();

        // 服务入口（会话创建处）：新会话写配置默认上限（设计文档 §4.1）
        var access = mock(ProjectAccessGuard.class);
        var service = new AgentRunService(access, repository, new AgentScenarioRegistry(), json,
                mock(AgentEventService.class), mock(AgentEventRepository.class),
                mock(AgentApprovalRepository.class), mock(com.shitulelv.aicollab.agent.application.AgentHistoryVisibilityService.class));
        var created = service.createSession(fixture.project(), fixture.user(),
                new CreateAgentSessionRequest("服务入口默认上限"));
        assertThat(jdbc.queryForObject(
                "SELECT max_aggregate_tokens FROM agent_session WHERE id=?",
                Long.class, created.id())).isEqualTo(2_000_000L);
    }
}
