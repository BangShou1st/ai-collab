package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentPlanningOperationService;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentRunService;
import com.shitulelv.aicollab.agent.application.AgentWorker;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;

import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.domain.tool.ControlledWriteAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.planning.application.TaskPlanCommandService;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import jakarta.validation.Validation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 主动暂停与输入续跑回归（真实 PostgreSQL）。
 *
 * <p>故障注入方式：在确定性时刻直接写暂停意图（或由替身工具/模型在已知时刻写入），
 * 用真实 claimNext / AgentWorker / 协调器 / 工具执行器推进，不等待租约、不靠 sleep。
 * 断言真实提交边界：状态、暂停意图、事件、invocation 状态与业务受理行数。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentPauseResumePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static AgentRepository repository;
    static AgentRunEventRecorder recorder;
    static PlatformTransactionManager txManager;

    private AgentContextAssembler assembler;
    private AgentEventService events;
    private RoutingAgentModelExecutor modelExecutor;
    private AgentApprovalService approvals;
    private AgentRuntimeCoordinator coordinator;
    private AgentRuntimeCoordinator realToolCoordinator;
    private final List<String> executedToolCalls = new CopyOnWriteArrayList<>();
    private AgentRecoveryJob recovery;
    private AgentWorker worker;
    private AgentWorker realWorker;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        recorder = new AgentRunEventRecorder(jdbc, json);
        repository = new AgentRepository(jdbc, json, recorder);
        txManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM agent_planning_operation_event");
        jdbc.update("DELETE FROM agent_planning_operation");
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
        when(assembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
            AgentRunView argumentRun = invocation.getArgument(0);
            return argumentRun == null ? null : context(argumentRun);
        });
        coordinator = coordinator(mock(AgentToolCallExecutor.class),
                new AgentContextProperties(false, 20_000, 40_000, 4_000, java.util.Map.of()),
                new AgentToolRegistry(List.of()));
        executedToolCalls.clear();
        realToolCoordinator = realExecutorCoordinator(
                new AgentToolRegistry(List.of(readStub("list_tasks"))), new AgentToolScheduler());
        worker = new AgentWorker(repository, coordinator, events, json);
        ReflectionTestUtils.setField(worker, "cancellation", new AgentCancellationService(repository));
        realWorker = new AgentWorker(repository, realToolCoordinator, events, json);
        ReflectionTestUtils.setField(realWorker, "cancellation", new AgentCancellationService(repository));
    }

    // ========== 1. QUEUED 暂停/继续：自动调度不领取 PAUSED；重复命令幂等 ==========

    @Test
    void queuedRunPausesAndResumesWithIdempotentCommands() {
        Fixture fixture = fixture("排队中的暂停");
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                fixture.goal(), false, "ITERATION_PLANNING", null);

        AgentRunView paused = repository.requestPause(fixture.project(), run.id());
        assertThat(paused.status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(pauseRequested(run.id())).isTrue();
        // PAUSED 不进入自动 claim
        assertThat(recovery.claim("worker-1", Duration.ofMinutes(6))).isEmpty();
        verify(events, times(1)).append(eq(fixture.project()), eq(run.id()),
                eq(AgentEventType.RUN_PAUSED), any());

        // 重复暂停幂等，无重复事件
        repository.requestPause(fixture.project(), run.id());
        verify(events, times(1)).append(any(), any(), eq(AgentEventType.RUN_PAUSED), any());

        AgentRunView resumed = repository.requestResume(fixture.project(), run.id());
        assertThat(resumed.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(pauseRequested(run.id())).isFalse();
        verify(events, times(1)).append(eq(fixture.project()), eq(run.id()),
                eq(AgentEventType.RUN_RESUMED), any());

        // 无新暂停意图时重复 resume 幂等返回当前运行，不二次记事件
        AgentRunView again = repository.requestResume(fixture.project(), run.id());
        assertThat(again.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(again.id()).isEqualTo(run.id());
        verify(events, times(1)).append(any(), any(), eq(AgentEventType.RUN_RESUMED), any());

        // 可立即调度，且原 runId 不变
        assertThat(recovery.claim("worker-2", Duration.ofMinutes(6))).isPresent();
    }

    // ========== 2. 重试等待期暂停：自动调度不领取；继续保留已耗重试次数 ==========

    @Test
    void retryableRunPauseBlocksSchedulingAndResumeKeepsRetryCount() {
        Fixture fixture = fixture("重试等待期暂停");
        AgentRunView run = runningRun(fixture);
        repository.recordFailure(run, "AI_MODEL_TIMEOUT", true);

        assertThat(runStatus(run.id())).isEqualTo("FAILED_RETRYABLE");
        assertThat(retryCount(run.id())).isEqualTo(1);

        AgentRunView paused = repository.requestPause(fixture.project(), run.id());
        assertThat(paused.status()).isEqualTo(AgentRunStatus.PAUSED);
        // 自动重试到期后也不得领取 PAUSED
        assertThat(recovery.claim("worker-late", Duration.ofMinutes(6))).isEmpty();

        AgentRunView resumed = repository.requestResume(fixture.project(), run.id());
        assertThat(resumed.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(retryCount(run.id())).isEqualTo(1);
        // 手动继续后可立即调度
        assertThat(recovery.claim("worker-next", Duration.ofMinutes(6))).isPresent();
    }

    // ========== 3. 模型在途暂停：响应保存、工具保持 PENDING；恢复按原身份继续 ==========

    @Test
    void inFlightResponseIsSavedThenRunPausesWithPendingTools() {
        Fixture fixture = fixture("在途请求暂停");
        AgentRunView run = runningRun(fixture);
        ModelTurnResult response = new ModelTurnResult("先查询任务",
                List.of(new ModelToolCall("call-inflight-1", "list_tasks",
                                json.createObjectNode().put("query", "q-1")),
                        new ModelToolCall("call-inflight-2", "list_tasks",
                                json.createObjectNode().put("query", "q-2"))),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L);
        // 模拟"模型请求在途时用户点暂停"：请求返回前暂停意图落库
        when(modelExecutor.resolveRequest(any())).thenReturn(AgentRuntimeBehaviorTest.resolved(true));
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            jdbc.update("UPDATE agent_run SET pause_requested_at=now() WHERE id=?", run.id());
            return response;
        });

        AgentWorkerOutcome outcome = realToolCoordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.PAUSED);
        // 已发出的请求允许完成并保存真实结果
        assertThat(modelTurnCount(run.id())).isEqualTo(1);
        // 暂停后不启动响应提出的新工具：invocation 保持 PENDING，未被 SKIPPED/失败
        assertThat(invocationStatuses(run.id())).containsExactly("PENDING", "PENDING");
        assertThat(skippedInvocationCount(run.id())).isZero();
        assertThat(consumedFlag(run.id())).isFalse();
        assertThat(executedToolCalls).isEmpty();

        // 继续：同一个 runId，复用已保存响应执行剩余调用，不新增模型请求
        repository.requestResume(fixture.project(), run.id());
        assertThat(recovery.claim("worker-resume", Duration.ofMinutes(6))).isPresent();
        AgentWorkerOutcome resumed = realToolCoordinator.advance(reload(fixture, run));

        assertThat(resumed.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(executedToolCalls).containsExactlyInAnyOrder("q-1", "q-2");
        assertThat(modelTurnCount(run.id())).isEqualTo(1);
        verify(modelExecutor, times(1)).callModel(any(), any(), any(), anyBoolean(), any());
    }

    // ========== 4. 完整文本已保存后暂停：真实 Worker 继续完成原答案，预算前置检查不丢结果 ==========

    @Test
    void savedTextAnswerSurvivesPauseAndWorkerBudgetPrecheck() {
        Fixture fixture = fixture("保存后暂停的文本");
        AgentRunView run = runningRun(fixture);
        String answer = "完整回答：已核对全部里程碑。";
        // 输入预算用满：旧的"下一轮预留"前置检查会在这里丢掉有效答案
        run = withCounters(run, run.maxInputTokens(), 0, 0, 0);
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");
        run = repository.recordModelTurnWithSettlement(run, plainTextTurn(answer), callId, "MODEL_TURN",
                AgentRunEventRecorder.UsageSettlement.fromRaw(null, 10, 10, 1L), "NATIVE_TOOLS", false);

        // 暂停意图 + worker 退出（租约过期）：接管只确认 PAUSED，不推进动作
        repository.requestPause(fixture.project(), run.id());
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '5 seconds' WHERE id=?", run.id());
        ClaimedAgentRun claim = recovery.claim("worker-takeover", Duration.ofMinutes(6)).orElseThrow();
        assertThat(worker.process(claim).status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(runStatus(run.id())).isEqualTo("PAUSED");
        assertThat(repository.pendingModelTurn(reload(fixture, run))).isPresent();
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());

        // 继续：经真实 Worker 入口消费已保存结果，新增模型请求为零，答案一次
        repository.requestResume(fixture.project(), run.id());
        ClaimedAgentRun resumedClaim = recovery.claim("worker-resume", Duration.ofMinutes(6)).orElseThrow();
        AgentWorkerOutcome done = worker.process(resumedClaim);

        assertThat(done.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(done.answer()).isEqualTo(answer);
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(countSteps(run.id(), "FINAL_ANSWER")).isEqualTo(1);
        assertThat(assistantMessages(run.sessionId())).containsExactly(answer);
        verify(events, times(1)).append(eq(fixture.project()), eq(run.id()),
                eq(AgentEventType.RUN_SUCCEEDED), any());
    }

    // ========== 5. 部分工具已完成：暂停保留 PENDING，继续只执行剩余项 ==========

    @Test
    void partialToolBatchPauseKeepsPendingAndResumeExecutesOnlyRemaining() {
        Fixture fixture = fixture("部分完成的批次");
        AgentRunView run = baseStateWithToolEvidence(fixture, new int[]{2, 2, 2, 2, 2, 2});
        ModelTurnResult response = new ModelTurnResult("继续核对任务分布",
                List.of(call("call-p1", "q-p1"), call("call-p2", "q-p2"),
                        call("call-p3", "q-p3"), call("call-p4", "q-p4")),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L);
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");
        run = repository.recordModelTurnWithSettlement(run, response, callId, "MODEL_TURN",
                AgentRunEventRecorder.UsageSettlement.fromRaw(null, 10, 10, 1L), "NATIVE_TOOLS", false);
        // 本 claim 完成 2 项后退出
        run = repository.recordToolResult(run, "list_tasks", toolInput(response.toolCalls().get(0)),
                successResult(), false);
        run = repository.recordToolResult(run, "list_tasks", toolInput(response.toolCalls().get(1)),
                successResult(), false);
        assertThat(toolCallsUsed(run.id())).isEqualTo(14);

        // 暂停意图 + 接管：只确认 PAUSED，剩余调用保持 PENDING、不误标 SKIPPED
        repository.requestPause(fixture.project(), run.id());
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '5 seconds' WHERE id=?", run.id());
        ClaimedAgentRun claim = recovery.claim("worker-takeover", Duration.ofMinutes(6)).orElseThrow();
        assertThat(realWorker.process(claim).status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(lastTurnInvocationStatuses(run.id()))
                .containsExactly("SUCCEEDED", "SUCCEEDED", "PENDING", "PENDING");
        assertThat(skippedInvocationCount(run.id())).isZero();
        assertThat(executedToolCalls).isEmpty();

        // 继续：只执行剩余 2 项，16 额度边界仍正确，已完成项不重复
        repository.requestResume(fixture.project(), run.id());
        ClaimedAgentRun resumedClaim = recovery.claim("worker-resume", Duration.ofMinutes(6)).orElseThrow();
        assertThat(realWorker.process(resumedClaim).status()).isEqualTo(AgentRunStatus.QUEUED);

        assertThat(executedToolCalls).containsExactlyInAnyOrder("q-p3", "q-p4");
        assertThat(toolCallsUsed(run.id())).isEqualTo(16);
        assertThat(skippedInvocationCount(run.id())).isZero();
        assertThat(modelTurnCount(run.id())).isEqualTo(7);
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
    }

    // ========== 6. 限流排队中的调用：未获准入不启动；已准入结果落库 ==========

    @Test
    void queuedCallsBehindSemaphoreDoNotStartAfterPause() throws Exception {
        Fixture fixture = fixture("信号量排队暂停");
        AgentRunView run = runningRun(fixture);
        CountDownLatch firstAdmitted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AgentTool gatedRead = new AgentTool() {
            @Override public String name() { return "list_tasks"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext ctx,
                    com.fasterxml.jackson.databind.JsonNode arguments) {
                String query = arguments.path("query").asText();
                if (query.equals("q-1")) {
                    try {
                        firstAdmitted.countDown();
                        // 持有限流许可等待主测试写入暂停意图：q-1 已获准入，q-2/q-3 仍在排队
                        assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                executedToolCalls.add(query);
                return new AgentToolResult(successResult(), List.of(), List.of());
            }
        };
        // 单许可调度器：q-1 确定性持锁，q-2/q-3 确定性排队，不依赖线程时序
        AgentToolScheduler singlePermit = new AgentToolScheduler() {
            private final Semaphore one = new Semaphore(1, true);
            @Override AutoCloseable acquire(AgentToolContext ctx) throws InterruptedException {
                one.acquire();
                return one::release;
            }
        };
        var gatedExecutor = new AgentToolCallExecutor(repository,
                new AgentToolRegistry(List.of(gatedRead)), new AgentCancellationService(repository),
                new AgentLoopGuard(), approvals, modelExecutor, new AgentToolResultSanitizer(json),
                json, events, singlePermit);
        var gatedCoordinator = coordinator(gatedExecutor,
                new AgentContextProperties(false, 20_000, 4_000, 2_000, java.util.Map.of()),
                new AgentToolRegistry(List.of(gatedRead)));
        ModelTurnResult response = new ModelTurnResult("批量核对",
                List.of(call("call-q1", "q-1"), call("call-q2", "q-2"), call("call-q3", "q-3")),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L);
        when(modelExecutor.resolveRequest(any())).thenReturn(AgentRuntimeBehaviorTest.resolved(true));
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any())).thenReturn(response);

        // q-1 已获准入开始执行后再落暂停意图：等价于用户在批次执行中点暂停
        var outcomeFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> gatedCoordinator.advance(run));
        assertThat(firstAdmitted.await(10, TimeUnit.SECONDS))
                .as("q-1 应已获得限流许可并开始执行").isTrue();
        jdbc.update("UPDATE agent_run SET pause_requested_at=now() WHERE id=?", run.id());
        proceed.countDown();
        AgentWorkerOutcome outcome = outcomeFuture.get(30, TimeUnit.SECONDS);

        // 已准入的调用结果落库；排队中的 q-2/q-3 未获准入，保持 PENDING
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(executedToolCalls).containsExactly("q-1");
        assertThat(invocationStatuses(run.id()))
                .containsExactly("SUCCEEDED", "PENDING", "PENDING");
        assertThat(skippedInvocationCount(run.id())).isZero();

        // 继续：q-2/q-3 按原身份执行，已完成结果不重复
        repository.requestResume(fixture.project(), run.id());
        assertThat(recovery.claim("worker-resume", Duration.ofMinutes(6))).isPresent();
        AgentWorkerOutcome resumed = gatedCoordinator.advance(reload(fixture, run));
        assertThat(resumed.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(executedToolCalls).containsExactly("q-1", "q-2", "q-3");
        assertThat(toolCallsUsed(run.id())).isEqualTo(3);
    }

    // ========== 7. 业务受理边界：暂停意图先落库则不受理；已受理的恢复零重复 ==========

    @Test
    void planningAcceptanceRespectsPauseIntentAndRecoversTheSameOperation() {
        Fixture fixture = fixture("请生成迭代规划草稿");
        AgentRunView run = runningRun(fixture);
        TaskPlanCommandService commands = mock(TaskPlanCommandService.class);
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        AgentPlanningOperationService operations = new AgentPlanningOperationService(
                jdbc, json, access, commands, mock(TaskPlanRepository.class),
                Validation.buildDefaultValidatorFactory().getValidator());
        ControlledWriteAgentTool planningTool = new ControlledWriteAgentTool() {
            @Override public String name() { return "start_task_plan"; }
            @Override public AgentToolResult execute(AgentToolContext ctx,
                    com.fasterxml.jackson.databind.JsonNode arguments) {
                return new AgentToolResult(operations.mutate(ctx, name(), arguments), List.of(), List.of());
            }
        };
        var planningCoordinator = realExecutorCoordinator(
                new AgentToolRegistry(List.of(planningTool)), new AgentToolScheduler());

        ModelToolCall planningCall = new ModelToolCall("call-plan-1", "start_task_plan",
                json.createObjectNode()
                        .put("title", "暂停续跑验收规划")
                        .put("goal", "生成本迭代规划草稿")
                        .put("planStartDate", java.time.LocalDate.now().toString())
                        .put("planDueDate", java.time.LocalDate.now().plusDays(30).toString())
                        .put("maxTaskCount", 10));
        ModelTurnResult response = new ModelTurnResult("开始生成规划",
                List.of(planningCall), ModelFinishReason.TOOL_CALLS,
                null, "OPENAI_COMPATIBLE", "model-a", 120L);
        String callId = repository.beginModelCall(fixture.project(), run.id(), "MODEL_TURN");
        run = repository.recordModelTurnWithSettlement(run, response, callId, "MODEL_TURN",
                AgentRunEventRecorder.UsageSettlement.fromRaw(null, 10, 10, 1L), "NATIVE_TOOLS", false);

        // 真实计划行：受理写入与后续读取路径使用同一身份
        // （attempt 与 plan 互相引用：先建 plan（空 attempt），再建 attempt，再回填）
        UUID planId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan(id,project_id,title,goal,plan_start_date,plan_due_date,
                  max_task_count,status,generation_seq,created_by)
                VALUES (?,?, '暂停续跑验收规划', '生成本迭代规划草稿', current_date,
                  current_date + interval '30 days', 10, 'SKELETON_GENERATING', 1, ?)
                """, planId, fixture.project(), fixture.user());
        jdbc.update("""
                INSERT INTO ai_task_plan_attempt(id,plan_id,attempt_no,generation_seq,stage,status,created_by)
                VALUES (?,?,1,1,'SKELETON','QUEUED',?)
                """, attemptId, planId, fixture.user());
        jdbc.update("UPDATE ai_task_plan SET active_attempt_id=? WHERE id=?", attemptId, planId);
        TaskPlanRecord plan = mock(TaskPlanRecord.class);
        when(plan.id()).thenReturn(planId);
        when(plan.generationSeq()).thenReturn(1L);
        when(plan.activeAttemptId()).thenReturn(attemptId);
        when(commands.create(any(), any(), any())).thenReturn(plan);

        // 暂停意图先落库：真实受理边界（运行行锁）拒绝新业务动作，真实规划写入不发生
        repository.requestPause(fixture.project(), run.id());
        var acceptCtx = new AgentToolContext(run.id(), fixture.project(), fixture.user(),
                "SUPERVISOR", false, 0)
                .withInvocationId(repository.invocationId(run, planningCall));
        assertThatThrownBy(() -> operations.mutate(acceptCtx, "start_task_plan", planningCall.arguments()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_PAUSED);
        verify(commands, never()).create(any(), any(), any());
        assertThat(operationCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-plan-1'",
                String.class, run.id())).isEqualTo("PENDING");

        // 恢复后（清除暂停意图）按原 invocation 身份受理一次
        jdbc.update("UPDATE agent_run SET pause_requested_at=NULL WHERE id=?", run.id());
        var accepted = operations.mutate(acceptCtx, "start_task_plan", planningCall.arguments());
        assertThat(accepted.path("operationId")).isNotNull();
        verify(commands, times(1)).create(any(), any(), any());
        assertThat(operationCount()).isEqualTo(1);

        // "业务已提交、工具结果未落库"的窗口：接管恢复复用原 operation，零重复受理
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '5 seconds' WHERE id=?", run.id());
        recovery.claim("worker-plan-recovery", Duration.ofMinutes(6)).orElseThrow();
        try (var scope = new AgentLeaseScope(
                jdbc.queryForObject("SELECT claim_version FROM agent_run WHERE id=?", Integer.class, run.id()))) {
            assertThat(planningCoordinator.advance(reload(fixture, run)).status())
                    .isEqualTo(AgentRunStatus.SUCCEEDED);
        }
        verify(commands, times(1)).create(any(), any(), any());
        assertThat(operationCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-plan-1'",
                String.class, run.id())).isEqualTo("SUCCEEDED");
    }

    private long operationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM agent_planning_operation", Long.class);
    }

    // ========== 8. 终态/等待外部输入不提供暂停与恢复 ==========

    @Test
    void terminalAndWaitingStatesRejectPauseAndResume() {
        Fixture fixture = fixture("终态保护");
        AgentRunView run = runningRun(fixture);
        AgentRunView finished = repository.recordFinal(run, "已完成", List.of());
        assertThat(runStatus(finished.id())).isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> repository.requestPause(fixture.project(), finished.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_PAUSABLE);
        assertThatThrownBy(() -> repository.requestResume(fixture.project(), finished.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_RESUMABLE);

        // 取消后的 PAUSED 不可恢复：终态不被复活（排队运行可直接暂停）
        AgentRunView other = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                "排队暂停后结束", false, "ITERATION_PLANNING", null);
        repository.requestPause(fixture.project(), other.id());
        assertThat(runStatus(other.id())).isEqualTo("PAUSED");
        repository.requestCancel(fixture.project(), other.id());
        assertThat(runStatus(other.id())).isEqualTo("CANCELED");
        assertThatThrownBy(() -> repository.requestResume(fixture.project(), other.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_RESUMABLE);

        // 等待澄清的运行不提供暂停（澄清回复走既有 /continue 入口）
        AgentRunView waiting = runningRun(fixture);
        AgentRunView waitRun = repository.recordWaitingForInput(waiting, "请确认范围？");
        assertThatThrownBy(() -> repository.requestPause(fixture.project(), waitRun.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_PAUSABLE);
    }

    // ========== 9. 控制事务中途失败：状态/标记/事件整体回滚 ==========

    @Test
    void controlTransactionFailureRollsBackStateMarkerAndEvent() {
        Fixture fixture = fixture("控制事务原子性");
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                fixture.goal(), false, "ITERATION_PLANNING", null);
        // 用真实事件服务替换 mock：RUN_PAUSED 事件与状态必须在同一事务内（生产为原子事件路径）
        var realEvents = new AgentEventService(
                new AgentEventRepository(jdbc, json),
                mock(AgentEventStreamService.class));
        ReflectionTestUtils.setField(recorder, "events", realEvents);
        try {
            new TransactionTemplate(txManager).execute(status -> {
                repository.requestPause(fixture.project(), run.id());
                status.setRollbackOnly();
                return null;
            });
        } finally {
            ReflectionTestUtils.setField(recorder, "events", events);
        }
        assertThat(runStatus(run.id())).isEqualTo("QUEUED");
        assertThat(pauseRequested(run.id())).isFalse();
        assertThat(countEvents(run.id(), "RUN_PAUSED")).isZero();
        // 回滚后命令可以重新执行
        assertThat(repository.requestPause(fixture.project(), run.id()).status())
                .isEqualTo(AgentRunStatus.PAUSED);
    }

    // ========== 10. 继续后的下一次请求按当前配置解析 ==========

    @Test
    void resumeIssuesNextRequestWithCurrentConfiguration() {
        Fixture fixture = fixture("暂停后换配置");
        AgentRunView run = runningRun(fixture);
        // 运行中暂停：意图落库 → worker 边界确认 PAUSED
        repository.requestPause(fixture.project(), run.id());
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '5 seconds' WHERE id=?", run.id());
        assertThat(worker.process(recovery.claim("worker-takeover", Duration.ofMinutes(6)).orElseThrow())
                .status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(runStatus(run.id())).isEqualTo("PAUSED");
        repository.requestResume(fixture.project(), run.id());

        String answer = "按当前配置给出的回答。";
        when(modelExecutor.resolveRequest(any())).thenReturn(AgentRuntimeBehaviorTest.resolved(true));
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(plainTextTurn(answer));

        AgentWorkerOutcome outcome = worker.process(
                recovery.claim("worker-again", Duration.ofMinutes(6)).orElseThrow());

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo(answer);
        // 恢复后的下一次确需模型请求时重新解析当前配置
        verify(modelExecutor, times(1)).resolveRequest(any());
        verify(modelExecutor, times(1)).callModel(any(), any(), any(), anyBoolean(), any());
    }

    // ========== 11. 暂停/暂停等待期的输入分流（服务端统一入口） ==========

    @Test
    void submitEntryRoutesResumeIntentsWithoutTouchingTheGoal() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        AgentRunService runs = new AgentRunService(access, repository,
                new AgentSkillRegistry(), json, events,
                mock(AgentEventRepository.class),
                mock(AgentApprovalRepository.class));
        Fixture fixture = fixture("输入续跑");
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                fixture.goal(), false, "ITERATION_PLANNING", null);
        String goalRevisionBefore = goalRevision(fixture.session());
        int userMessagesBefore = userMessages(fixture.session()).size();

        repository.requestPause(fixture.project(), run.id());

        // 明确续跑：恢复同一个 run，不新建派生运行、不追加 USER 消息、不改目标与 goalRevision
        AgentRunView resumed = runs.submit(fixture.project(), fixture.session(), fixture.user(),
                new SubmitAgentMessageRequest("继续", null, null, run.id().toString()));
        assertThat(resumed.id()).isEqualTo(run.id());
        assertThat(resumed.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(runCount(fixture)).isEqualTo(1);
        assertThat(userMessages(fixture.session())).hasSize(userMessagesBefore);
        assertThat(goalRevision(fixture.session())).isEqualTo(goalRevisionBefore);

        // 已恢复后的重复续跑表达：幂等返回当前运行，不退回普通 submit 新建任务
        AgentRunView duplicate = runs.submit(fixture.project(), fixture.session(), fixture.user(),
                new SubmitAgentMessageRequest("继续刚才的任务", null, null, run.id().toString()));
        assertThat(duplicate.id()).isEqualTo(run.id());
        assertThat(runCount(fixture)).isEqualTo(1);

        // 歧义与普通新内容：保留暂停语义，不静默创建新任务改写当前工作状态
        repository.requestPause(fixture.project(), run.id());
        assertThatThrownBy(() -> runs.submit(fixture.project(), fixture.session(), fixture.user(),
                new SubmitAgentMessageRequest("如何继续", null, null, run.id().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_PAUSED_INPUT);
        assertThatThrownBy(() -> runs.submit(fixture.project(), fixture.session(), fixture.user(),
                new SubmitAgentMessageRequest("不继续了", null, null, run.id().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_PAUSED_INPUT);
        assertThatThrownBy(() -> runs.submit(fixture.project(), fixture.session(), fixture.user(),
                new SubmitAgentMessageRequest("继续检查另一个项目的风险", null, null, run.id().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_PAUSED_INPUT);
        assertThat(runCount(fixture)).isEqualTo(1);
        assertThat(goalRevision(fixture.session())).isEqualTo(goalRevisionBefore);

        // 运行作用域绑定：绑定的 runId 与当前运行不符时明确拒绝
        assertThatThrownBy(() -> runs.submit(fixture.project(), fixture.session(), fixture.user(),
                new SubmitAgentMessageRequest("继续", null, null, UUID.randomUUID().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_FOUND);

        // 暂停等待期（RUNNING + 意图）：抢先恢复被明确拒绝
        jdbc.update("""
                UPDATE agent_run SET status='RUNNING', pause_requested_at=now(),
                  claim_started_at=now(), lease_expires_at=now()+interval '5 minutes'
                WHERE id=?
                """, run.id());
        assertThatThrownBy(() -> runs.submit(fixture.project(), fixture.session(), fixture.user(),
                new SubmitAgentMessageRequest("继续", null, null, run.id().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_PAUSE_PENDING);
    }

    // ========== 12. 旧 claim 与新控制收口：旧 worker 被租约拦截 ==========

    @Test
    void staleClaimCannotConfirmPauseAfterTakeover() {
        Fixture fixture = fixture("旧 claim 拦截");
        AgentRunView run = runningRun(fixture);
        repository.requestPause(fixture.project(), run.id());
        int oldClaimVersion = run.version();

        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '5 seconds' WHERE id=?", run.id());
        ClaimedAgentRun newClaim = recovery.claim("worker-new", Duration.ofMinutes(6)).orElseThrow();
        assertThat(newClaim.version()).isGreaterThan(oldClaimVersion);

        // 旧 worker 试图确认暂停：租约拦截，运行不被旧 claim 收口
        try (var scope = new AgentLeaseScope(oldClaimVersion)) {
            assertThatThrownBy(() -> repository.pauseIfRequested(run))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(runStatus(run.id())).isEqualTo("RUNNING");

        // 新 claim 收口暂停
        assertThat(repository.pauseIfRequested(reload(fixture, run))).isTrue();
        assertThat(runStatus(run.id())).isEqualTo("PAUSED");
        assertThat(reload(fixture, run).id()).isEqualTo(newClaim.id());
    }

    // ========== 13. F1: 迟到/重复的绑定续跑输入在终态不退化为普通新任务 ==========

    @Test
    void staleBoundResumeInputOnTerminalRunStaysInControlScope() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        AgentRunService runs = new AgentRunService(access, repository,
                new AgentSkillRegistry(), json, events,
                mock(AgentEventRepository.class),
                mock(AgentApprovalRepository.class));
        Fixture fixture = fixture("恢复后快速完成");
        UUID project = fixture.project();
        UUID session = fixture.session();
        UUID user = fixture.user();
        AgentRunView run = repository.createRun(project, session, user,
                fixture.goal(), false, "ITERATION_PLANNING", null);
        // 原运行暂停→恢复→快速完成
        repository.requestPause(project, run.id());
        repository.requestResume(project, run.id());
        jdbc.update("UPDATE agent_run SET status='SUCCEEDED',finished_at=now() WHERE id=?", run.id());
        String goalRevisionBefore = goalRevision(session);
        int userMessagesBefore = userMessages(session).size();

        // 迟到/重复的"继续"（仍绑定原 runId）：返回真实终态，不新建第二个"继续"任务
        AgentRunView returned = runs.submit(project, session, user,
                new SubmitAgentMessageRequest("继续", null, null, run.id().toString()));
        assertThat(returned.id()).isEqualTo(run.id());
        assertThat(returned.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(runCount(fixture)).isEqualTo(1);
        assertThat(userMessages(session)).hasSize(userMessagesBefore);
        assertThat(goalRevision(session)).isEqualTo(goalRevisionBefore);

        // 终态绑定 + 非续跑内容：明确业务提示，不静默新建任务改写工作状态
        assertThatThrownBy(() -> runs.submit(project, session, user,
                new SubmitAgentMessageRequest("帮我写一份新报告", null, null, run.id().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_RESUMABLE);
        assertThat(runCount(fixture)).isEqualTo(1);
        assertThat(goalRevision(session)).isEqualTo(goalRevisionBefore);

        // 取消后的绑定输入：不复活、不新建，返回真实状态
        AgentRunView other = repository.createRun(project, session, user,
                "另一个目标", false, "ITERATION_PLANNING", null);
        repository.requestPause(project, other.id());
        repository.requestCancel(project, other.id());
        AgentRunView canceledView = runs.submit(project, session, user,
                new SubmitAgentMessageRequest("继续", null, null, other.id().toString()));
        assertThat(canceledView.id()).isEqualTo(other.id());
        assertThat(canceledView.status()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(runCount(fixture)).isEqualTo(2);

        // 未绑定控制作用域的真正新任务仍按普通提交处理
        AgentRunView fresh = runs.submit(project, session, user,
                new SubmitAgentMessageRequest("开始一个全新任务", null, null, null));
        assertThat(fresh.id()).isNotEqualTo(run.id()).isNotEqualTo(other.id());
        assertThat(fresh.goal()).isEqualTo("开始一个全新任务");
        assertThat(runCount(fixture)).isEqualTo(3);
        // 绑定的控制输入都不追加 USER 消息；只有普通提交的新任务追加一条
        assertThat(userMessages(session)).hasSize(userMessagesBefore + 2);
        assertThat(userMessages(session).get(userMessages(session).size() - 1))
                .isEqualTo("开始一个全新任务");
    }

    // ========== 14. F5: 摘要首次请求与重压缩的持久化暂停准入 ==========

    @Test
    void summaryFirstRequestIsAdmittedThroughPauseBoundary() {
        Fixture fixture = fixture("摘要暂停准入");
        AgentRunView run = runningRun(fixture);
        // 摘要候选与工作状态都属于运行所在会话（runningRun 另建会话）
        var candidates = repository.listMessages(fixture.project(), run.sessionId(), 10);
        var composition = new AgentModelMessageComposer.Composition(List.of(),
                AgentModelMessageComposer.CompositionStats.empty(), null, candidates);

        // 暂停意图先于首次摘要准入落库：不创建请求身份、不发模型请求
        repository.requestPause(fixture.project(), run.id());
        when(modelExecutor.callModelWithoutTools(any(), any()))
                .thenReturn(plainTextTurn("不应被调用"));
        new AgentContextSummarizer(repository, modelExecutor, json)
                .maybeSummarize(run, composition, 100_000);
        verify(modelExecutor, never()).callModelWithoutTools(any(), any());
        assertThat(summaryAttemptCount(run.id())).isZero();
        assertThat(recompressAttemptCount(run.id())).isZero();

        // 清除暂停意图（恢复边界）后：首次摘要正常准入并按原语义提交
        jdbc.update("UPDATE agent_run SET pause_requested_at=NULL WHERE id=?", run.id());
        when(modelExecutor.callModelWithoutTools(any(), any()))
                .thenReturn(plainTextTurn("仍有效约束：保留原目标。"));
        new AgentContextSummarizer(repository, modelExecutor, json)
                .maybeSummarize(reload(fixture, run), composition, 100_000);
        verify(modelExecutor, times(1)).callModelWithoutTools(any(), any());
        assertThat(summaryAttemptCount(run.id())).isEqualTo(1);
        assertThat(summaryAttemptOutcome(run.id())).isEqualTo("COMMITTED");
        assertThat(summaryText(run.sessionId())).contains("仍有效约束：保留原目标。");
    }

    @Test
    void inFlightSummaryWithPauseIntentDoesNotStartRecompress() {
        Fixture fixture = fixture("首次摘要在途暂停");
        AgentRunView run = runningRun(fixture);
        var candidates = repository.listMessages(fixture.project(), run.sessionId(), 10);
        var composition = new AgentModelMessageComposer.Composition(List.of(),
                AgentModelMessageComposer.CompositionStats.empty(), null, candidates);
        java.util.concurrent.atomic.AtomicInteger summaryCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(modelExecutor.callModelWithoutTools(any(), any())).thenAnswer(invocation -> {
            if (summaryCalls.incrementAndGet() == 1) {
                // 首次摘要在途时用户请求暂停：意图先于重压缩准入落库
                repository.requestPause(fixture.project(), run.id());
                return plainTextTurn("仍有效约束。".repeat(300)); // 超长，不合格
            }
            return plainTextTurn("仍有效约束：保留原目标。");
        });

        new AgentContextSummarizer(repository, modelExecutor, json)
                .maybeSummarize(run, composition, 100_000);

        // 只有首次请求；重压缩身份未创建；首次真实用量照常结算（控制结果，不是摘要失败）
        assertThat(summaryCalls.get()).isEqualTo(1);
        assertThat(summaryAttemptCount(run.id())).isEqualTo(1);
        assertThat(recompressAttemptCount(run.id())).isZero();
        assertThat(summaryAttemptOutcome(run.id())).isEqualTo("PAUSED");
        assertThat(runInputActual(run.id())).isPositive();
        // 不虚报覆盖：无上一份有效摘要时不写入 summary
        assertThat(summaryText(run.sessionId())).isNull();
    }

    @Test
    void coordinatorSettlesPauseAfterSummaryAdmissionWithoutMainRequest() {
        Fixture fixture = fixture("摘要后协调器收口");
        AgentRunView run = runningRun(fixture);
        // 受控组合结果：携带真实候选消息触发 7b 摘要流程（composerV2 路径）
        var candidates = repository.listMessages(fixture.project(), run.sessionId(), 10);
        var composer = mock(AgentModelMessageComposer.class);
        when(composer.composeV2(any(), any(), any(), any(), any(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(new AgentModelMessageComposer.Composition(
                        List.of(new ModelMessage.User(fixture.goal())),
                        AgentModelMessageComposer.CompositionStats.empty(), null, candidates));
        var summaryCoordinator = new AgentRuntimeCoordinator(
                repository, assembler, new AgentSkillRegistry(), new AgentPlanService(repository, json),
                new AgentToolRegistry(List.of()), new AgentCancellationService(repository), modelExecutor,
                new AgentConvergencePolicy(), json, events,
                new AgentContextProperties(true, 20_000, 4_000, 2_000, java.util.Map.of()),
                composer, mock(AgentToolCallExecutor.class),
                new AgentContextSummarizer(repository, modelExecutor, json));

        java.util.concurrent.atomic.AtomicInteger summaryCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(modelExecutor.resolveRequest(any())).thenReturn(AgentRuntimeBehaviorTest.resolved(true));
        when(modelExecutor.callModelWithoutTools(any(), any())).thenAnswer(invocation -> {
            if (summaryCalls.incrementAndGet() == 1) {
                repository.requestPause(fixture.project(), run.id());
                return plainTextTurn("仍有效约束。".repeat(300)); // 超长，不合格
            }
            return plainTextTurn("仍有效约束：保留原目标。");
        });

        int claimVersion = jdbc.queryForObject(
                "SELECT claim_version FROM agent_run WHERE id=?", Integer.class, run.id());
        AgentWorkerOutcome outcome;
        try (var scope = new AgentLeaseScope(claimVersion)) {
            outcome = summaryCoordinator.advance(run);
        }

        // 首次摘要已发生并允许完成；重压缩未获准入；主请求准入被拒，
        // 协调器经现有安全边界收口 PAUSED——主请求未因辅助摘要的通用处理继续出站
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(summaryCalls.get()).isEqualTo(1);
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(recompressAttemptCount(run.id())).isZero();
        assertThat(summaryAttemptOutcome(run.id())).isEqualTo("PAUSED");
        assertThat(modelTurnCount(run.id())).isZero();
        assertThat(runStatus(run.id())).isEqualTo("PAUSED");
    }

    @Test
    void summaryAdmissionRejectsExpiredClaim() {
        Fixture fixture = fixture("过期 claim 摘要准入");
        AgentRunView run = runningRun(fixture);
        int currentClaimVersion = jdbc.queryForObject(
                "SELECT claim_version FROM agent_run WHERE id=?", Integer.class, run.id());

        // 过期 claim：租约校验拒绝创建摘要请求身份，不发模型请求
        try (var scope = new AgentLeaseScope(currentClaimVersion - 1)) {
            assertThatThrownBy(() -> repository.beginSummaryAttempt(run))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(summaryAttemptCount(run.id())).isZero();

        // 持有有效 claim 时准入正常
        try (var scope = new AgentLeaseScope(currentClaimVersion)) {
            assertThat(repository.beginSummaryAttempt(run)).isNotNull();
        }
        assertThat(summaryAttemptCount(run.id())).isEqualTo(1);
    }

    // ========== 15. C1: 未处理的绑定输入在活跃非终态也不进入普通提交 ==========

    @Test
    void boundInputOnActiveRunNeverFallsIntoCreateRun() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        AgentRunService runs = new AgentRunService(access, repository,
                new AgentSkillRegistry(), json, events,
                mock(AgentEventRepository.class),
                mock(AgentApprovalRepository.class));
        Fixture fixture = fixture("活跃态绑定输入");
        UUID project = fixture.project();
        UUID session = fixture.session();
        UUID user = fixture.user();

        // 排队中 + 绑定非续跑文本：明确拒绝，不新建任务
        AgentRunView queued = repository.createRun(project, session, user,
                fixture.goal(), false, "ITERATION_PLANNING", null);
        String goalRevisionBefore = goalRevision(session);
        assertThatThrownBy(() -> runs.submit(project, session, user,
                new SubmitAgentMessageRequest("帮我写一份新报告", null, null, queued.id().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_RESUMABLE);
        assertThat(runCount(fixture)).isEqualTo(1);
        assertThat(goalRevision(session)).isEqualTo(goalRevisionBefore);

        // 重试等待（FAILED_RETRYABLE）+ 迟到"继续"：不新建、不复活重试（重试走既有入口）
        recovery.claim("worker-c1", Duration.ofMinutes(6)).orElseThrow();
        AgentRunView failed = repository.findRun(project, queued.id()).orElseThrow();
        repository.recordFailure(failed, "AI_MODEL_TIMEOUT", true);
        assertThat(runStatus(queued.id())).isEqualTo("FAILED_RETRYABLE");
        assertThatThrownBy(() -> runs.submit(project, session, user,
                new SubmitAgentMessageRequest("继续", null, null, queued.id().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_RESUMABLE);
        assertThat(runCount(fixture)).isEqualTo(1);
        assertThat(runStatus(queued.id())).isEqualTo("FAILED_RETRYABLE");

        // 等待澄清（WAITING_FOR_USER_INPUT）+ 迟到"继续"：不新建（澄清走原 /continue 入口）
        AgentRunView waiting = repository.createRun(project, session, user,
                "等待澄清的目标", false, "ITERATION_PLANNING", null);
        recovery.claim("worker-c1b", Duration.ofMinutes(6)).orElseThrow();
        repository.recordWaitingForInput(
                repository.findRun(project, waiting.id()).orElseThrow(), "请确认范围？");
        assertThat(runStatus(waiting.id())).isEqualTo("WAITING_FOR_USER_INPUT");
        int userMessagesBefore = userMessages(session).size();
        assertThatThrownBy(() -> runs.submit(project, session, user,
                new SubmitAgentMessageRequest("继续", null, null, waiting.id().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_RESUMABLE);
        assertThat(runCount(fixture)).isEqualTo(2);
        assertThat(userMessages(session)).hasSize(userMessagesBefore);
        assertThat(goalRevision(session)).isEqualTo(goalRevisionBefore);

        // 运行中 + 绑定非续跑文本：不新建
        AgentRunView running = repository.createRun(project, session, user,
                "运行中的目标", false, "ITERATION_PLANNING", null);
        recovery.claim("worker-c1c", Duration.ofMinutes(6)).orElseThrow();
        assertThat(runStatus(running.id())).isEqualTo("RUNNING");
        assertThatThrownBy(() -> runs.submit(project, session, user,
                new SubmitAgentMessageRequest("帮我核对另一件事", null, null, running.id().toString())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_RUN_NOT_RESUMABLE);
        assertThat(runCount(fixture)).isEqualTo(3);
        // 绑定的控制输入都不追加 USER 消息；USER 消息只来自各 run 的普通创建
        assertThat(userMessages(session)).hasSize(userMessagesBefore + 1);
        assertThat(userMessages(session).get(userMessages(session).size() - 1))
                .isEqualTo("运行中的目标");
    }

    // ---------- 现场构造 ----------

    private AgentTool readStub(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext context,
                    com.fasterxml.jackson.databind.JsonNode arguments) {
                executedToolCalls.add(arguments.path("query").asText());
                return new AgentToolResult(successResult(), List.of(), List.of());
            }
        };
    }

    private com.fasterxml.jackson.databind.JsonNode successResult() {
        return json.createObjectNode().put("status", "SUCCEEDED")
                .set("tasks", json.createArrayNode().add("替身任务"));
    }

    private ModelToolCall call(String id, String query) {
        return new ModelToolCall(id, "list_tasks", json.createObjectNode().put("query", query));
    }

    private com.fasterxml.jackson.databind.JsonNode toolInput(ModelToolCall call) {
        var input = json.createObjectNode();
        input.put("toolCallId", call.id());
        input.set("arguments", call.arguments());
        return input;
    }

    private ModelTurnResult plainTextTurn(String content) {
        return new ModelTurnResult(content, List.of(), ModelFinishReason.STOP,
                null, "OPENAI_COMPATIBLE", "model-a", 120L);
    }

    private AgentRuntimeCoordinator realExecutorCoordinator(AgentToolRegistry registry, AgentToolScheduler scheduler) {
        var realExecutor = new AgentToolCallExecutor(repository, registry,
                new AgentCancellationService(repository), new AgentLoopGuard(), approvals, modelExecutor,
                new AgentToolResultSanitizer(json), json, events, scheduler);
        return coordinator(realExecutor,
                new AgentContextProperties(false, 20_000, 4_000, 2_000, java.util.Map.of()), registry);
    }

    private AgentRuntimeCoordinator coordinator(AgentToolCallExecutor executor, AgentContextProperties properties,
            AgentToolRegistry registry) {
        var cancellation = new AgentCancellationService(repository);
        return new AgentRuntimeCoordinator(
                repository, assembler, new AgentSkillRegistry(), new AgentPlanService(repository, json),
                registry, cancellation, modelExecutor,
                new AgentConvergencePolicy(), json, events, properties,
                new AgentModelMessageComposer(repository, mock(AgentMemoryService.class), json), executor,
                new AgentContextSummarizer(repository, modelExecutor, json));
    }

    private AgentRunView runningRun(Fixture fixture) {
        var session = repository.createSession(fixture.project(), fixture.user(), "暂停续跑验收");
        var run = repository.createRun(fixture.project(), session.id(), fixture.user(),
                fixture.goal(), false, "ITERATION_PLANNING", null);
        recovery.claim("worker-1", Duration.ofMinutes(6)).orElseThrow();
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    private AgentRunView baseStateWithToolEvidence(Fixture fixture, int[] toolCallsPerTurn) {
        AgentRunView run = runningRun(fixture);
        for (int turn = 0; turn < toolCallsPerTurn.length; turn++) {
            List<ModelToolCall> calls = new ArrayList<>();
            for (int i = 0; i < toolCallsPerTurn[turn]; i++) {
                calls.add(new ModelToolCall("call-" + turn + "-" + i, "list_tasks",
                        json.createObjectNode().put("query", "背景核对-" + turn + "-" + i)));
            }
            run = repository.recordModelTurn(run, new ModelTurnResult("调用证据 " + turn, calls,
                    ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L), "NATIVE_TOOLS");
            for (ModelToolCall call : calls) {
                run = repository.recordToolResult(run, "list_tasks", toolInput(call),
                        json.createObjectNode().put("status", "SUCCEEDED"), false);
            }
        }
        jdbc.update("""
                UPDATE agent_step SET output_json=jsonb_set(COALESCE(output_json,'{}'::jsonb),'{batchHandled}','true'::jsonb)
                WHERE run_id=? AND type='MODEL_TURN'
                """, run.id());
        return run;
    }

    private AgentRunView withCounters(AgentRunView run, Integer inputUsed, Integer inputActual,
            Integer outputUsed, Integer outputActual) {
        jdbc.update("""
                UPDATE agent_run SET input_tokens_used=COALESCE(?,input_tokens_used),
                  input_tokens_actual=COALESCE(?,input_tokens_actual),
                  output_tokens_used=COALESCE(?,output_tokens_used),
                  output_tokens_actual=COALESCE(?,output_tokens_actual)
                WHERE id=?
                """, inputUsed, inputActual, outputUsed, outputActual, run.id());
        return repository.findRun(run.projectId(), run.id()).orElseThrow();
    }

    private AgentRunView reload(Fixture fixture, AgentRunView run) {
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM agent_run WHERE id=?", String.class, runId);
    }

    private boolean pauseRequested(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pause_requested_at IS NOT NULL FROM agent_run WHERE id=?", Boolean.class, runId));
    }

    private int retryCount(UUID runId) {
        return jdbc.queryForObject("SELECT retry_count FROM agent_run WHERE id=?", Integer.class, runId);
    }

    private int toolCallsUsed(UUID runId) {
        return jdbc.queryForObject("SELECT tool_calls_used FROM agent_run WHERE id=?", Integer.class, runId);
    }

    private int modelTurnCount(UUID runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE run_id=? AND type='MODEL_TURN'", Integer.class, runId);
    }

    private List<String> invocationStatuses(UUID runId) {
        return jdbc.queryForList(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? ORDER BY turn_sequence,ordinal",
                String.class, runId);
    }

    /** 最近一个模型轮次所属调用的状态（历史轮次的调用已全部结算，不计入断言）。 */
    private List<String> lastTurnInvocationStatuses(UUID runId) {
        return jdbc.queryForList("""
                SELECT i.status FROM agent_tool_invocation i
                WHERE i.run_id=? AND i.turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')
                ORDER BY i.ordinal
                """, String.class, runId, runId);
    }

    private int skippedInvocationCount(UUID runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_tool_invocation WHERE run_id=? AND status='SKIPPED'",
                Integer.class, runId);
    }

    private boolean consumedFlag(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COALESCE((output_json->>'batchHandled')::boolean,false) FROM agent_step
                WHERE run_id=? AND type='MODEL_TURN' ORDER BY sequence_no DESC LIMIT 1
                """, Boolean.class, runId));
    }

    private List<String> assistantMessages(UUID sessionId) {
        return jdbc.queryForList(
                "SELECT content FROM agent_message WHERE session_id=? AND role='ASSISTANT' ORDER BY created_at",
                String.class, sessionId);
    }

    private List<String> userMessages(UUID sessionId) {
        return jdbc.queryForList(
                "SELECT content FROM agent_message WHERE session_id=? AND role='USER'", String.class, sessionId);
    }

    private int countSteps(UUID runId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=? AND type=?",
                Integer.class, runId, type);
    }

    private long runCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_run WHERE project_id=? AND session_id=?",
                Long.class, fixture.project(), fixture.session());
    }

    private String goalRevision(UUID sessionId) {
        return jdbc.queryForObject(
                "SELECT s.working_state->>'goalRevision' FROM agent_session s WHERE s.id=?",
                String.class, sessionId);
    }

    private int summaryAttemptCount(UUID runId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE run_id=? AND reason='CONTEXT_SUMMARY'",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    private int recompressAttemptCount(UUID runId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE run_id=? AND reason='CONTEXT_SUMMARY_RECOMPRESS'",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    private String summaryAttemptOutcome(UUID runId) {
        return jdbc.queryForObject("""
                SELECT output_json->>'status' FROM agent_step
                WHERE run_id=? AND reason='CONTEXT_SUMMARY' ORDER BY sequence_no DESC LIMIT 1
                """, String.class, runId);
    }

    private int runInputActual(UUID runId) {
        return jdbc.queryForObject(
                "SELECT input_tokens_actual FROM agent_run WHERE id=?", Integer.class, runId);
    }

    private String summaryText(UUID sessionId) {
        return jdbc.queryForObject(
                "SELECT s.working_state->'summary'->>'text' FROM agent_session s WHERE s.id=?",
                String.class, sessionId);
    }

    private int countEvents(UUID runId, String type) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_run_event WHERE run_id=? AND type=?", Integer.class, runId, type);
        return count == null ? 0 : count;
    }

    private com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext context(AgentRunView run) {
        return new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(), "OWNER", false,
                AgentPageContext.empty(), AgentRuntimeLimits.forSkill(run.skillCode()), 0, List.of());
    }

    private record Fixture(UUID user, UUID project, UUID session, String goal) {
    }

    private Fixture fixture(String goal) {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "pause-" + user.toString().substring(0, 8), "test-only-hash", "Pause");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "暂停续跑验收", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        var session = repository.createSession(project, user, "暂停续跑验收");
        return new Fixture(user, project, session.id(), goal);
    }
}
