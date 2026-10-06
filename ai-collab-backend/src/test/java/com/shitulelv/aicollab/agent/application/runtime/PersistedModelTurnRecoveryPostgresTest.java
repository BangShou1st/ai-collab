package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2 已持久化模型结果恢复回归（真实 PostgreSQL）。
 *
 * <p>故障注入方式：先通过真实仓储把 MODEL_TURN 提交（该事务已提交），再把运行置回
 * 未消费现场（清除 {@code batchHandled} 并保持 RUNNING），等价于
 * "MODEL_TURN 已提交 → 最终收尾尚未提交"之间进程退出。不使用长 sleep，
 * 也不给生产增加测试专用执行引擎。</p>
 *
 * <p>验收语义：接管复用已保存结果、零新增模型调用；普通文本、[QUESTIONS]、
 * 预算部分回答分别保持原有终态语义；最终消息/步骤/事件与结果消费恰好一次；
 * 取消、租约/版本与旧 worker 迟到提交仍受原保护。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PersistedModelTurnRecoveryPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static AgentRepository repository;
    static AgentRunEventRecorder recorder;

    private AgentContextAssembler assembler;
    private AgentEventService events;
    private RoutingAgentModelExecutor modelExecutor;
    private AgentToolCallExecutor toolExecutor;
    private AgentApprovalService approvals;
    private AgentRuntimeCoordinator coordinator;
    /** 配对回归用：outputReserve 与生产一致的协调器（现有 coordinator 的大预留值会掩盖 needsFinalRequest 边界）。 */
    private AgentRuntimeCoordinator pairCoordinator;
    /** 工具批次补修回归用：真实工具执行器 + 安全替身工具（记录真实执行次数）。 */
    private AgentRuntimeCoordinator realToolCoordinator;
    private final java.util.List<String> executedToolCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
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
        assembler = mock(AgentContextAssembler.class);
        events = mock(AgentEventService.class);
        modelExecutor = mock(RoutingAgentModelExecutor.class);
        toolExecutor = mock(AgentToolCallExecutor.class);
        ReflectionTestUtils.setField(recorder, "events", events);
        recovery = new AgentRecoveryJob(repository);
        // 统一按入参组装上下文；判空是因为 Mockito 重新打桩时会以 null 参数触发旧 answer
        when(assembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
            AgentRunView argumentRun = invocation.getArgument(0);
            return argumentRun == null ? null : context(argumentRun);
        });
        coordinator = coordinator(toolExecutor);
        pairCoordinator = coordinator(toolExecutor,
                new AgentContextProperties(false, 20_000, 4_000, 2_000, java.util.Map.of()));
        approvals = mock(AgentApprovalService.class);
        var stubRegistry = new AgentToolRegistry(java.util.List.of(listTasksStub(), startTaskPlanStub()));
        var realToolExecutor = new AgentToolCallExecutor(repository, stubRegistry,
                new AgentCancellationService(repository), new AgentLoopGuard(), approvals, modelExecutor,
                new AgentToolResultSanitizer(json), json, events, new AgentToolScheduler());
        realToolCoordinator = coordinator(realToolExecutor,
                new AgentContextProperties(false, 20_000, 4_000, 2_000, java.util.Map.of()), stubRegistry);
    }

    /** 安全替身：只读查询工具，记录真实执行次数（执行一次记一次参数标记）。 */
    private com.shitulelv.aicollab.agent.domain.tool.AgentTool listTasksStub() {
        return new com.shitulelv.aicollab.agent.domain.tool.AgentTool() {
            @Override public String name() { return "list_tasks"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public com.shitulelv.aicollab.agent.domain.tool.AgentToolResult execute(
                    com.shitulelv.aicollab.agent.domain.tool.AgentToolContext context,
                    com.fasterxml.jackson.databind.JsonNode arguments) {
                executedToolCalls.add(arguments.path("query").asText());
                return new com.shitulelv.aicollab.agent.domain.tool.AgentToolResult(
                        json.createObjectNode().put("status", "SUCCEEDED")
                                .set("tasks", json.createArrayNode().add("替身任务")),
                        java.util.List.of(), java.util.List.of());
            }
        };
    }

    /** 安全替身：审批写工具（start_task_plan 同族）。提案受理不调用 execute，真实写不发生。 */
    private com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool startTaskPlanStub() {
        return new com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool() {
            @Override public String name() { return "start_task_plan"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public com.shitulelv.aicollab.agent.domain.tool.AgentToolResult execute(
                    com.shitulelv.aicollab.agent.domain.tool.AgentToolContext context,
                    com.fasterxml.jackson.databind.JsonNode arguments) {
                executedToolCalls.add("start_task_plan");
                return new com.shitulelv.aicollab.agent.domain.tool.AgentToolResult(
                        json.createObjectNode().put("status", "SUCCEEDED"),
                        java.util.List.of(), java.util.List.of());
            }
            @Override public com.fasterxml.jackson.databind.JsonNode normalize(
                    com.shitulelv.aicollab.agent.domain.tool.AgentToolContext context,
                    com.fasterxml.jackson.databind.JsonNode arguments) { return arguments; }
            @Override public com.fasterxml.jackson.databind.JsonNode diff(
                    com.shitulelv.aicollab.agent.domain.tool.AgentToolContext context,
                    com.fasterxml.jackson.databind.JsonNode arguments) {
                return json.createObjectNode().put("summary", "替身规划草稿");
            }
        };
    }

    /**
     * 1. 普通文本：MODEL_TURN 已提交、收尾前退出，接管复用已保存响应，
     *    模型零新增调用，最终回答/步骤/事件恰好一次。
     *
     * <p>锁定原问题：旧实现 {@code pendingModelTurn} 只恢复含工具调用的轮次，
     * 该窗口会再次请求模型并产生第二个回答。</p>
     */
    @Test
    void persistedPlainTextTurnIsReusedWithoutAnotherModelCall() {
        Fixture fixture = fixture("汇总这周进展");
        String answer = "本周共完成 3 项任务，2 项存在延期风险。";
        CrashWindow window = crashWindowWithPlainText(fixture, answer);

        assertThat(repository.pendingModelTurn(window.run())).isPresent();

        AgentWorkerOutcome outcome = coordinator.advance(reload(fixture, window.run()));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo(answer);
        // 结果来自已持久化的响应：没有第二次出站请求，也没有重新解析模型配置
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        verify(modelExecutor, never()).resolveRequest(any());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, window.run().id()))
                .isEqualTo("SUCCEEDED");
        assertThat(countSteps(window.run().id(), "FINAL_ANSWER")).isEqualTo(1);
        assertThat(countAssistantMessages(window.run().sessionId())).isEqualTo(1);
        assertThat(assistantMessages(window.run().sessionId())).containsExactly(answer);
        verify(events, times(1)).append(eq(fixture.project()), eq(window.run().id()),
                eq(AgentEventType.RUN_SUCCEEDED), any());
    }

    /**
     * 2. 重复接管 / 重复推进不重复消费、不重复记账。
     */
    @Test
    void repeatedTakeoverDoesNotConsumeTheSavedTurnTwice() {
        Fixture fixture = fixture("重复接管");
        String answer = "已核对里程碑状态。";
        CrashWindow window = crashWindowWithPlainText(fixture, answer);
        long stepsBefore = countSteps(window.run().id(), null);

        coordinator.advance(reload(fixture, window.run()));

        // 该轮次的结果已被消费：不再作为"待恢复结果"出现，重复接管也不能再次使用
        assertThat(repository.pendingModelTurn(reload(fixture, window.run()))).isEmpty();
        // 只追加一个最终答案步骤；已持久化轮次没有产生第二次模型调用
        assertThat(countSteps(window.run().id(), null)).isEqualTo(stepsBefore + 1);
        assertThat(countSteps(window.run().id(), "FINAL_ANSWER")).isEqualTo(1);
        assertThat(countAssistantMessages(window.run().sessionId())).isEqualTo(1);
        assertThat(repository.claimNext("worker-again", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                Duration.ofMinutes(6))).isEmpty();
        verify(events, times(1)).append(any(), any(), eq(AgentEventType.RUN_SUCCEEDED), any());

        // 即使有人把运行强行置回 RUNNING，已消费轮次也不会被当作可复用结果
        jdbc.update("UPDATE agent_run SET status='RUNNING' WHERE id=?", window.run().id());
        assertThat(repository.pendingModelTurn(reload(fixture, window.run()))).isEmpty();
    }

    /**
     * 3. [QUESTIONS] 文本恢复到等待输入，并保持原有状态语义。
     */
    @Test
    void persistedQuestionsTurnRecoversIntoWaitingForUserInput() {
        Fixture fixture = fixture("需要澄清的请求");
        String question = "[QUESTIONS]\n请确认周报覆盖的时间范围？";
        CrashWindow window = crashWindowWithPlainText(fixture, question);

        AgentWorkerOutcome outcome = coordinator.advance(reload(fixture, window.run()));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.WAITING_FOR_USER_INPUT);
        assertThat(outcome.answer()).isEqualTo(question);
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, window.run().id()))
                .isEqualTo("WAITING_FOR_USER_INPUT");
        assertThat(repository.pendingModelTurn(reload(fixture, window.run()))).isEmpty();
        assertThat(assistantMessages(window.run().sessionId())).containsExactly(question);
        verify(events, times(1)).append(eq(fixture.project()), eq(window.run().id()),
                eq(AgentEventType.WAITING_FOR_USER_INPUT), any());
    }

    /**
     * 4. 预算强制收尾且核心动作未完成的文本仍保持"预算部分回答"语义。
     */
    @Test
    void persistedBudgetFinalizeTurnKeepsPartialAnswerSemantics() {
        Fixture fixture = fixture("请生成迭代规划草稿");
        // 构造收尾边界：下一次模型轮次即为收尾轮，已有成功工具证据但核心动作 start_task_plan 未成功受理
        AgentRunView run = runningRun(fixture);
        for (int i = 0; i < 6; i++) {
            run = repository.recordModelTurn(run, toolCallTurn("调用证据 " + i, "call-" + i));
            run = repository.recordToolResult(run, "list_tasks",
                    toolInput("call-" + i), json.createObjectNode().put("status", "SUCCEEDED"), false);
        }
        AgentRunView beforeFinal = run;
        String partial = "预算内只完成了背景核对，规划草稿尚未生成。";
        run = repository.recordModelTurn(run, plainTextTurn(partial));
        clearConsumption(run.id());
        when(assembler.assemble(any(), any(), any())).thenReturn(context(beforeFinal));

        AgentWorkerOutcome outcome = coordinator.advance(reload(fixture, run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_BUDGET_EXCEEDED");
        assertThat(outcome.answer()).isEqualTo(partial);
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, run.id()))
                .isEqualTo("BUDGET_EXCEEDED");
        assertThat(assistantMessages(run.sessionId())).containsExactly(partial);
        assertThat(repository.pendingModelTurn(reload(fixture, run))).isEmpty();
    }

    /**
     * 5. 已取消的运行在恢复入口保持取消语义，不会消费已保存结果、也不会请求模型。
     */
    @Test
    void canceledRunStillEndsAsCanceledWithoutConsumingTheSavedTurn() {
        Fixture fixture = fixture("取消后的收尾");
        CrashWindow window = crashWindowWithPlainText(fixture, "不应发送的回答");
        repository.requestCancel(fixture.project(), window.run().id());
        // 取消请求已记录但状态仍是 RUNNING（等待 worker 边界）
        jdbc.update("UPDATE agent_run SET status='RUNNING', finished_at=NULL WHERE id=?", window.run().id());

        AgentWorkerOutcome outcome = coordinator.advance(reload(fixture, window.run()));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(outcome.errorCode()).isEqualTo("RUN_CANCELLED");
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, window.run().id()))
                .isEqualTo("CANCELED");
        assertThat(assistantMessages(window.run().sessionId())).isEmpty();
    }

    /**
     * 6. 旧 worker 迟到提交：新 claim 的租约拦截仍然生效，已保存结果不被旧 claim 越界消费。
     */
    @Test
    void staleClaimCannotConsumeTheSavedTurnAfterTakeover() {
        Fixture fixture = fixture("旧 claim 迟到收尾");
        CrashWindow window = crashWindowWithPlainText(fixture, "新 worker 应负责的收尾");
        var oldClaim = window.claim();

        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '5 seconds' WHERE id=?", window.run().id());
        ClaimedAgentRun newClaim = recovery.claim("worker-new", Duration.ofMinutes(6)).orElseThrow();
        assertThat(newClaim.version()).isGreaterThan(oldClaim.version());
        AgentRunView afterTakeover = reload(fixture, window.run());

        try (var scope = new AgentLeaseScope(oldClaim.version())) {
            assertThatThrownBy(() -> coordinator.advance(afterTakeover))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_run WHERE id=?", String.class, window.run().id()))
                .isEqualTo("RUNNING");
        assertThat(assistantMessages(window.run().sessionId())).isEmpty();
        assertThat(repository.pendingModelTurn(afterTakeover)).isPresent();

        // 新 claim 继续收尾：结果被消费一次并正确终态
        try (var scope = new AgentLeaseScope(newClaim.version())) {
            assertThat(coordinator.advance(afterTakeover).status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        }
        assertThat(assistantMessages(window.run().sessionId())).containsExactly("新 worker 应负责的收尾");
        assertThat(repository.pendingModelTurn(reload(fixture, window.run()))).isEmpty();
    }

    /**
     * 7. 模型配置在结果保存后被禁用/删除：无需新请求的有效结果不丢失；
     *    需要下一次请求时仍按当前配置解析（此处确认恢复路径根本没有解析配置）。
     */
    @Test
    void savedResultSurvivesRemovedModelConfiguration() {
        Fixture fixture = fixture("配置删除后的收尾");
        CrashWindow window = crashWindowWithPlainText(fixture, "配置删除后仍应交付的回答");
        when(modelExecutor.resolveRequest(any())).thenThrow(new BusinessException(
                ErrorCode.AI_PROVIDER_UNAVAILABLE, "Agent 模型配置已禁用"));

        AgentWorkerOutcome outcome = coordinator.advance(reload(fixture, window.run()));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo("配置删除后仍应交付的回答");
        verify(modelExecutor, never()).resolveRequest(any());
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 8. 既有工具批次恢复继续通过：恢复批次走工具执行器（recovery=true），不再请求模型。
     */
    @Test
    void persistedToolBatchStillRecoversThroughTheToolExecutor() {
        Fixture fixture = fixture("工具批次恢复");
        AgentRunView run = runningRun(fixture);
        run = repository.recordModelTurn(run, toolCallTurn("先查询任务", "call-recover"));
        // 恢复批次固定走 recoveryBatch=true + 非本次请求解析的执行模式（source_mode 校验来源）
        when(toolExecutor.executeCalls(any(), any(), any(), any(), any(), any(), eq(true), eq(false)))
                .thenReturn(new AgentWorkerOutcome(AgentRunStatus.QUEUED, null, null, null));

        AgentWorkerOutcome outcome = coordinator.advance(reload(fixture, run));

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(toolExecutor).executeCalls(any(), any(), any(), any(), any(), any(), eq(true), eq(false));
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 9. 空内容轮次不作为可复用结果（维持原错误语义，不会静默记成功）。
     *    直接从数据库构造空内容轮次：查询条件本身必须排除它。
     */
    @Test
    void blankTextTurnIsNotTreatedAsAReusableResult() {
        Fixture fixture = fixture("空响应语义");
        AgentRunView run = runningRun(fixture);
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json)
                VALUES (?,?,'MODEL_TURN',?::jsonb)
                """, run.id(), 1, "{\"content\":\"\",\"toolCalls\":[]}");

        assertThat(repository.pendingModelTurn(reload(fixture, run))).isEmpty();
        // 可复用结果必须同时满足"未消费"和"有正文或有工具调用"
        jdbc.update("""
                UPDATE agent_step SET output_json=?::jsonb WHERE run_id=? AND type='MODEL_TURN'
                """, "{\"content\":\"   \",\"toolCalls\":[]}", run.id());
        assertThat(repository.pendingModelTurn(reload(fixture, run))).isEmpty();
    }

    // ========== R2 限定补修：同一份请求前状态，"正常完成" vs "保存后退出并接管" 配对回归 ==========
    //
    // 每个用例构造两份相同的请求前状态：runN 走正常路径（真实出站一次请求并消费），
    // runR 用真实写入路径保存同一响应（含持久化收尾意图）后置于崩溃现场，接管消费。
    // 比较答案、终态、步骤、消息、事件与新增模型调用数。旧版本在这些边界上的失败
    // 已由 docs/acceptance-evidence/2026-10-06/recovery-review/ 的一次性探针锁定。

    /**
     * 10. 第 8 次模型请求是合法收尾轮（模型轮次边界）：保存后接管保留原完整回答。
     *     旧实现在接管时按落库后的 8 轮先判 EXHAUSTED，用工具证据兜底文字覆盖答案。
     */
    @Test
    void modelTurnBoundaryFinalizeAnswerSurvivesTakeoverOnBothPaths() {
        String answer = "完整回答：本周完成 3 项，2 项有延期风险。";
        ModelTurnResult response = plainTextTurn(answer);

        // 正常路径：请求前 7 轮 → decide FINALIZE（第 8 轮即收尾轮）
        Fixture normalFixture = fixture("汇总这周进展");
        AgentRunView runN = baseStateWithToolEvidence(normalFixture, 7);
        assertDecision(normalFixture, runN, AgentConvergencePolicy.Mode.FINALIZE);
        modelReturns(response);
        AgentWorkerOutcome normal = pairCoordinator.advance(runN);

        // 恢复路径：同一请求前状态，响应已按真实写入路径落库（finalizing=true），接管消费
        Fixture recoveryFixture = fixture("汇总这周进展");
        AgentRunView runR = baseStateWithToolEvidence(recoveryFixture, 7);
        AgentRunView saved = saveResponseForTakeover(runR, response, true);
        AgentWorkerOutcome takeover = takeoverAdvance(recoveryFixture, saved);

        assertThat(normal.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(normal.answer()).isEqualTo(answer);
        assertThat(takeover.status()).isEqualTo(normal.status());
        assertThat(takeover.answer()).isEqualTo(normal.answer());
        // 两次推进合计恰好一次出站请求；恢复既不请求模型也不解析配置
        verify(modelExecutor, times(1)).callModel(any(), any(), any(), anyBoolean(), any());
        verify(modelExecutor, times(1)).resolveRequest(any());
        // 两条路径的步骤/消息/事件一致，恰好一次收尾
        assertThat(runStatus(runN.id())).isEqualTo("SUCCEEDED");
        assertThat(runStatus(runR.id())).isEqualTo("SUCCEEDED");
        assertThat(modelTurnCount(runN.id())).isEqualTo(modelTurnCount(runR.id()));
        assertThat(countSteps(runR.id(), "FINAL_ANSWER")).isEqualTo(1);
        assertThat(assistantMessages(runR.sessionId())).containsExactly(answer);
        verify(events, times(1)).append(eq(recoveryFixture.project()), eq(runR.id()),
                eq(AgentEventType.RUN_SUCCEEDED), any());
        assertThat(repository.pendingModelTurn(reload(recoveryFixture, runR))).isEmpty();
    }

    /**
     * 11. 步骤边界同理：剩余步骤恰为"收尾请求+最终回答"时发出的请求也是合法收尾轮；
     *     保存后剩余步骤只剩 1（EXHAUSTED），接管不得改写意图，也不预留另一轮模型请求。
     */
    @Test
    void stepBoundaryFinalizeAnswerSurvivesTakeoverOnBothPaths() {
        String answer = "完整回答：步骤边界上的收尾结果。";
        ModelTurnResult response = plainTextTurn(answer);
        // 6 轮共 16 次成功工具调用 → stepsUsed=22、toolCallsUsed=16 → 请求前 FINALIZE
        Fixture normalFixture = fixture("汇总这周进展");
        AgentRunView runN = baseStateWithToolEvidence(normalFixture, new int[]{4, 3, 3, 2, 2, 2});
        assertDecision(normalFixture, runN, AgentConvergencePolicy.Mode.FINALIZE);
        modelReturns(response);
        AgentWorkerOutcome normal = pairCoordinator.advance(runN);

        Fixture recoveryFixture = fixture("汇总这周进展");
        AgentRunView runR = baseStateWithToolEvidence(recoveryFixture, new int[]{4, 3, 3, 2, 2, 2});
        AgentRunView saved = saveResponseForTakeover(runR, response, true);
        // 保存后剩余步骤 1：若接管先看准入会判 EXHAUSTED——此处确认它不参与消费判定
        AgentConvergencePolicy.Decision afterSave = new AgentConvergencePolicy().decide(
                reload(recoveryFixture, saved), AgentRuntimeLimits.forSkill("ITERATION_PLANNING"),
                repository.listSteps(recoveryFixture.project(), saved.id()));
        assertThat(afterSave.mode()).isEqualTo(AgentConvergencePolicy.Mode.EXHAUSTED);
        AgentWorkerOutcome takeover = takeoverAdvance(recoveryFixture, saved);

        assertThat(normal.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(takeover.status()).isEqualTo(normal.status());
        assertThat(takeover.answer()).isEqualTo(answer);
        verify(modelExecutor, times(1)).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(runStatus(runR.id())).isEqualTo("SUCCEEDED");
        assertThat(countSteps(runR.id(), "FINAL_ANSWER")).isEqualTo(1);
        assertThat(assistantMessages(runR.sessionId())).containsExactly(answer);
        assertThat(repository.pendingModelTurn(reload(recoveryFixture, runR))).isEmpty();
    }

    /**
     * 12. 实际输入刚超过有效限额（纯文本响应）：正常与恢复路径都不得记成功；
     *     恢复用量不再次累计（请求准入曾通过、提供商实际用量把真实累计推过上限）。
     */
    @Test
    void actualInputOversetStopsTextAnswerOnBothPaths() {
        ModelTurnResult response = textTurnWithUsage("不应发布的回答。",
                new ModelUsage(11_000, 300));

        // 正常路径：used=40000 时请求准入通过（剩余 10000 > 估算），实际累计 40000+11000=51000 越限
        Fixture normalFixture = fixture("汇总这周进展");
        AgentRunView runN = baseStateWithToolEvidence(normalFixture, 1);
        runN = withCounters(runN, 40_000, 40_000, 0, 0);
        modelReturns(response);
        AgentWorkerOutcome normal = pairCoordinator.advance(runN);

        // 恢复路径：同一响应已落库（finalizing=false），接管时必须执行同一输入超限检查
        Fixture recoveryFixture = fixture("汇总这周进展");
        AgentRunView runR = baseStateWithToolEvidence(recoveryFixture, 1);
        runR = withCounters(runR, 40_000, 40_000, 0, 0);
        AgentRunView saved = saveResponseForTakeover(runR, response, false);
        Integer actualBeforeTakeover = runActual(saved.id(), "input_tokens_actual");
        AgentWorkerOutcome takeover = takeoverAdvance(recoveryFixture, saved);

        assertThat(normal.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(normal.answer()).isNull();
        assertThat(takeover.status()).isEqualTo(normal.status());
        assertThat(takeover.answer()).isNull();
        verify(modelExecutor, times(1)).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(runStatus(runR.id())).isEqualTo("BUDGET_EXCEEDED");
        assertThat(runActual(saved.id(), "input_tokens_actual")).isEqualTo(actualBeforeTakeover);
        assertThat(settlementCount(saved.id())).isEqualTo(1);
        assertThat(assistantMessages(runR.sessionId())).isEmpty();
        assertThat(countSteps(runR.id(), "FINAL_ANSWER")).isEqualTo(0);
    }

    /**
     * 13. 实际输入刚超限（工具批次响应）：文本不记成功之外，还不得新增工具执行；
     *     待处理调用保持 PENDING，不产生业务写入。
     */
    @Test
    void actualInputOversetStopsToolBatchOnBothPaths() {
        ModelTurnResult response = new ModelTurnResult("先查询任务",
                List.of(new ModelToolCall("call-overset", "list_tasks",
                        json.createObjectNode().put("query", "overset"))),
                ModelFinishReason.TOOL_CALLS, new ModelUsage(11_000, 300),
                "OPENAI_COMPATIBLE", "model-a", 120L);

        Fixture normalFixture = fixture("汇总这周进展");
        AgentRunView runN = baseStateWithToolEvidence(normalFixture, 1);
        runN = withCounters(runN, 40_000, 40_000, 0, 0);
        modelReturns(response);
        AgentWorkerOutcome normal = pairCoordinator.advance(runN);

        Fixture recoveryFixture = fixture("汇总这周进展");
        AgentRunView runR = baseStateWithToolEvidence(recoveryFixture, 1);
        runR = withCounters(runR, 40_000, 40_000, 0, 0);
        AgentRunView saved = saveResponseForTakeover(runR, response, false);
        Integer actualBeforeTakeover = runActual(saved.id(), "input_tokens_actual");
        AgentWorkerOutcome takeover = takeoverAdvance(recoveryFixture, saved);

        assertThat(normal.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(takeover.status()).isEqualTo(normal.status());
        verify(modelExecutor, times(1)).callModel(any(), any(), any(), anyBoolean(), any());
        // 恢复路径没有执行任何工具：调用保持 PENDING，无新工具结果步骤，用量不再次累计
        verify(toolExecutor, never()).executeCalls(any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean());
        // 恢复路径没有执行任何工具：批次终止时调用按既有语义收口为 SKIPPED（BATCH_ENDED），
        // 没有产生新的工具结果步骤，也没有业务写入
        assertThat(jdbc.queryForObject("""
                SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-overset'
                """, String.class, saved.id())).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("""
                SELECT result_json->>'status' FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-overset'
                """, String.class, saved.id())).isEqualTo("SKIPPED");
        assertThat(countSteps(saved.id(), "TOOL_CALL_COMPLETED")).isEqualTo(1);
        assertThat(runActual(saved.id(), "input_tokens_actual")).isEqualTo(actualBeforeTakeover);
        assertThat(runStatus(saved.id())).isEqualTo("BUDGET_EXCEEDED");
    }

    /**
     * 14. 输出剩余额度触发 needsFinalRequest、decide 仍为 CONTINUE：本轮请求真实采用
     *     收尾意图（核心动作未完成 → 预算部分完成），正常与恢复路径语义一致，正文保留。
     *     旧实现接管重新 decide 仍得 CONTINUE，把本应 BUDGET_EXCEEDED 的部分完成记成 SUCCEEDED。
     */
    @Test
    void needsFinalRequestIntentSurvivesTakeoverAsBudgetPartial() {
        String partial = "预算内只完成了背景核对，规划草稿尚未生成。";
        ModelTurnResult response = plainTextTurn(partial);
        var policy = new AgentConvergencePolicy();
        var limits = AgentRuntimeLimits.forSkill("ITERATION_PLANNING");

        // 正常路径：5 次成功背景查询、无核心动作；输出剩余 2500 < 预留 4000 → needsFinalRequest
        Fixture normalFixture = fixture("请生成迭代规划草稿");
        AgentRunView runN = baseStateWithToolEvidence(normalFixture, 5);
        runN = withCounters(runN, null, null, 17_500, 17_500);
        assertDecision(normalFixture, runN, AgentConvergencePolicy.Mode.CONTINUE);
        assertThat(policy.needsFinalRequest(reload(normalFixture, runN), limits,
                repository.listSteps(normalFixture.project(), runN.id()), 1, 4_000)).isTrue();
        modelReturns(response);
        AgentWorkerOutcome normal = pairCoordinator.advance(runN);

        // 恢复路径：同一响应按真实写入路径持久化（finalizing=true），接管不重新推导
        Fixture recoveryFixture = fixture("请生成迭代规划草稿");
        AgentRunView runR = baseStateWithToolEvidence(recoveryFixture, 5);
        runR = withCounters(runR, null, null, 17_500, 17_500);
        AgentRunView saved = saveResponseForTakeover(runR, response, true);
        AgentWorkerOutcome takeover = takeoverAdvance(recoveryFixture, saved);

        assertThat(normal.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(normal.answer()).isEqualTo(partial);
        assertThat(takeover.status()).isEqualTo(normal.status());
        assertThat(takeover.answer()).isEqualTo(normal.answer());
        verify(modelExecutor, times(1)).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(runStatus(runR.id())).isEqualTo("BUDGET_EXCEEDED");
        assertThat(assistantMessages(runR.sessionId())).containsExactly(partial);
        assertThat(repository.pendingModelTurn(reload(recoveryFixture, runR))).isEmpty();
    }

    /**
     * 15. 另一方向：请求时 decide=CONTINUE（finalizing=false 已随响应持久化），保存后
     *     计数使 decide 变为 FINALIZE——接管不得按新计数改写原请求意图（旧实现会把
     *     非收尾轮误按收尾处理成预算部分完成）。
     */
    @Test
    void postSaveRecountDoesNotRewriteNonFinalizingIntent() {
        String answer = "模型在普通轮次直接给出的回答。";
        ModelTurnResult response = plainTextTurn(answer);
        var limits = AgentRuntimeLimits.forSkill("ITERATION_PLANNING");

        // 正常路径：6 轮 → decide CONTINUE，输出预算充足 → needsFinalRequest=false，普通轮收尾
        Fixture normalFixture = fixture("请生成迭代规划草稿");
        AgentRunView runN = baseStateWithToolEvidence(normalFixture, 6);
        assertDecision(normalFixture, runN, AgentConvergencePolicy.Mode.CONTINUE);
        assertThat(new AgentConvergencePolicy().needsFinalRequest(reload(normalFixture, runN), limits,
                repository.listSteps(normalFixture.project(), runN.id()), 1, 4_000)).isFalse();
        modelReturns(response);
        AgentWorkerOutcome normal = pairCoordinator.advance(runN);

        // 恢复路径：保存后 7 轮 → decide 变为 FINALIZE；已持久化的 false 意图不被改写
        Fixture recoveryFixture = fixture("请生成迭代规划草稿");
        AgentRunView runR = baseStateWithToolEvidence(recoveryFixture, 6);
        AgentRunView saved = saveResponseForTakeover(runR, response, false);
        AgentConvergencePolicy.Decision afterSave = new AgentConvergencePolicy().decide(
                reload(recoveryFixture, saved), limits,
                repository.listSteps(recoveryFixture.project(), saved.id()));
        assertThat(afterSave.mode()).isEqualTo(AgentConvergencePolicy.Mode.FINALIZE);
        AgentWorkerOutcome takeover = takeoverAdvance(recoveryFixture, saved);

        assertThat(normal.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(takeover.status()).isEqualTo(normal.status());
        assertThat(takeover.answer()).isEqualTo(answer);
        verify(modelExecutor, times(1)).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(runStatus(runR.id())).isEqualTo("SUCCEEDED");
        assertThat(assistantMessages(runR.sessionId())).containsExactly(answer);
        assertThat(repository.pendingModelTurn(reload(recoveryFixture, runR))).isEmpty();
    }

    /**
     * 16. 旧元数据兼容：缺少 finalizing 标记的未收口记录（元数据引入前的历史轮次）
     *     按当前持久事实保守回退——核心动作已无要求按普通轮次完成，核心动作未发生
     *     按收尾轮记预算部分完成；两种回退都保留已有答案、都不再请求模型。
     *     无法还原的信息：该响应出站时是否为模型可见的收尾指令、以及当时的预算语境
     *     （输出预留/输入估算随落库后的计数变化），因此不能一律记成功。
     */
    @Test
    void legacyTurnWithoutIntentMetadataFallsBackConservatively() {
        // 无核心动作要求：按普通轮次处理 → 正常完成
        Fixture noCoreFixture = fixture("汇总这周进展");
        AgentRunView runA = runningRun(noCoreFixture);
        String plainAnswer = "历史记录缺少意图标记的回答。";
        runA = repository.recordModelTurn(runA, plainTextTurn(plainAnswer));
        clearConsumption(runA.id());
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '5 seconds' WHERE id=?", runA.id());
        recovery.claim("worker-legacy-a", Duration.ofMinutes(6)).orElseThrow();
        AgentWorkerOutcome plainOutcome = pairCoordinator.advance(reload(noCoreFixture, runA));
        assertThat(plainOutcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(plainOutcome.answer()).isEqualTo(plainAnswer);
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());

        // 核心动作未发生：按收尾轮处理 → 预算部分完成，正文保留
        Fixture coreFixture = fixture("请生成迭代规划草稿");
        AgentRunView runB = baseStateWithToolEvidence(coreFixture, 2);
        String coreAnswer = "历史部分回答：只完成了背景核对。";
        runB = repository.recordModelTurn(runB, plainTextTurn(coreAnswer));
        clearConsumption(runB.id());
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '5 seconds' WHERE id=?", runB.id());
        recovery.claim("worker-legacy-b", Duration.ofMinutes(6)).orElseThrow();
        AgentWorkerOutcome coreOutcome = pairCoordinator.advance(reload(coreFixture, runB));
        assertThat(coreOutcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(coreOutcome.errorCode()).isEqualTo("AGENT_BUDGET_EXCEEDED");
        assertThat(coreOutcome.answer()).isEqualTo(coreAnswer);
        verify(modelExecutor, times(0)).callModel(any(), any(), any(), anyBoolean(), any());
        assertThat(assistantMessages(runB.sessionId())).containsExactly(coreAnswer);
        assertThat(repository.pendingModelTurn(reload(coreFixture, runB))).isEmpty();
    }

    // ========== 工具批次恢复补修：总额度只计新增、旧工具响应不经收尾否决 ==========
    //
    // 关键批次使用真实工具执行器与安全替身工具（记录真实执行次数），不只断言 mock 参数。
    // 旧版本（整批计入总额度 / 旧工具响应被核心动作回退否决）的失败由
    // docs/acceptance-evidence/2026-10-06/recovery-review/tool-batch-review/ 的探针锁定，
    // 本节用例先在临时回退的生产代码上确认失败，再随修复通过。

    /**
     * 17. 部分完成的批次：总额度 16、已用 12、合法批次 4，先完成 2 项再退出。
     *     接管只执行剩余 2 项，最终计数 16；已完成结果不重复执行/记录，
     *     剩余调用不被错误 SKIPPED。旧实现按整批 4 项重计（14+4=18>16）提前拒绝。
     */
    @Test
    void partialToolBatchRecoveryExecutesOnlyTheRemainingCalls() {
        Fixture fixture = fixture("汇总这周进展");
        AgentRunView run = baseStateWithToolEvidence(fixture, new int[]{2, 2, 2, 2, 2, 2});
        ModelTurnResult response = new ModelTurnResult("继续核对任务分布",
                List.of(toolCall("call-p1", "q-p1"), toolCall("call-p2", "q-p2"),
                        toolCall("call-p3", "q-p3"), toolCall("call-p4", "q-p4")),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L);
        // 12 + 4 = 16 恰好在额度内；本轮 worker 完成 2 项后退出（真实记录路径落库）
        AgentRunView saved = saveResponseForTakeover(run, response, false);
        saved = repository.recordToolResult(saved, "list_tasks", toolInput(response.toolCalls().get(0)),
                successToolResult(), false);
        saved = repository.recordToolResult(saved, "list_tasks", toolInput(response.toolCalls().get(1)),
                successToolResult(), false);
        assertThat(toolCallsUsed(saved.id())).isEqualTo(14);

        AgentWorkerOutcome takeover = realTakeoverAdvance(fixture, saved);

        assertThat(takeover.status()).isEqualTo(AgentRunStatus.QUEUED);
        // 真实执行器只执行了剩余 2 项：已完成 2 项按原 invocation 身份复用，不再执行
        assertThat(executedToolCalls).containsExactlyInAnyOrder("q-p3", "q-p4");
        assertThat(toolCallsUsed(saved.id())).isEqualTo(16);
        assertThat(countSteps(saved.id(), "TOOL_CALL_COMPLETED")).isEqualTo(16);
        assertThat(invocationStatuses(saved.id())).containsOnly("SUCCEEDED");
        assertThat(skippedInvocationCount(saved.id())).isZero();
        assertThat(runStatus(saved.id())).isEqualTo("QUEUED");
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 18. 原批次全部完成但未收尾：恢复复用全部结果，不因 used+整批 再次超限，
     *     也不再执行任何工具。旧实现按 16+4=20 误判超限并 SKIPPED 全部调用。
     */
    @Test
    void fullyCompletedToolBatchRecoveryReusesAllResultsWithoutRecharge() {
        Fixture fixture = fixture("汇总这周进展");
        AgentRunView run = baseStateWithToolEvidence(fixture, new int[]{2, 2, 2, 2, 2, 2});
        ModelTurnResult response = new ModelTurnResult("最后一批核对",
                List.of(toolCall("call-f1", "q-f1"), toolCall("call-f2", "q-f2"),
                        toolCall("call-f3", "q-f3"), toolCall("call-f4", "q-f4")),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L);
        AgentRunView saved = saveResponseForTakeover(run, response, false);
        for (ModelToolCall call : response.toolCalls()) {
            saved = repository.recordToolResult(saved, "list_tasks", toolInput(call),
                    successToolResult(), false);
        }
        assertThat(toolCallsUsed(saved.id())).isEqualTo(16);

        AgentWorkerOutcome takeover = realTakeoverAdvance(fixture, saved);

        assertThat(takeover.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(executedToolCalls).isEmpty();
        assertThat(toolCallsUsed(saved.id())).isEqualTo(16);
        assertThat(countSteps(saved.id(), "TOOL_CALL_COMPLETED")).isEqualTo(16);
        assertThat(skippedInvocationCount(saved.id())).isZero();
        assertThat(runStatus(saved.id())).isEqualTo("QUEUED");
    }

    /**
     * 19. 剩余调用确实超过总额度仍拒绝：已用 14、批次 4、已完成 1，剩余 3 项
     *     需要 17 > 16。修复不得把总量检查放松掉。
     */
    @Test
    void trulyOverBudgetRemainingCallsStillRejected() {
        Fixture fixture = fixture("汇总这周进展");
        AgentRunView run = baseStateWithToolEvidence(fixture, new int[]{2, 2, 2, 2, 2, 3});
        ModelTurnResult response = new ModelTurnResult("再查一批",
                List.of(toolCall("call-o1", "q-o1"), toolCall("call-o2", "q-o2"),
                        toolCall("call-o3", "q-o3"), toolCall("call-o4", "q-o4")),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L);
        AgentRunView saved = saveResponseForTakeover(run, response, false);
        saved = repository.recordToolResult(saved, "list_tasks", toolInput(response.toolCalls().get(0)),
                successToolResult(), false);
        assertThat(toolCallsUsed(saved.id())).isEqualTo(14);

        AgentWorkerOutcome takeover = realTakeoverAdvance(fixture, saved);

        assertThat(takeover.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(executedToolCalls).isEmpty();
        assertThat(toolCallsUsed(saved.id())).isEqualTo(14);
        assertThat(skippedInvocationCount(saved.id())).isEqualTo(3);
        assertThat(runStatus(saved.id())).isEqualTo("BUDGET_EXCEEDED");
    }

    /**
     * 20. 原批次超过单轮上限仍拒绝（单轮数量按原批次校验，不因部分完成而放松）。
     */
    @Test
    void overSingleTurnLimitBatchStillRejected() {
        Fixture fixture = fixture("汇总这周进展");
        AgentRunView run = baseStateWithToolEvidence(fixture, 2);
        ModelTurnResult response = new ModelTurnResult("一批五项",
                List.of(toolCall("call-t1", "q-t1"), toolCall("call-t2", "q-t2"),
                        toolCall("call-t3", "q-t3"), toolCall("call-t4", "q-t4"),
                        toolCall("call-t5", "q-t5")),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L);
        AgentRunView saved = saveResponseForTakeover(run, response, false);
        saved = repository.recordToolResult(saved, "list_tasks", toolInput(response.toolCalls().get(0)),
                successToolResult(), false);
        saved = repository.recordToolResult(saved, "list_tasks", toolInput(response.toolCalls().get(1)),
                successToolResult(), false);

        AgentWorkerOutcome takeover = realTakeoverAdvance(fixture, saved);

        assertThat(takeover.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(executedToolCalls).isEmpty();
        assertThat(skippedInvocationCount(saved.id())).isEqualTo(3);
    }

    /**
     * 21. 旧元数据（缺少 finalizing 标记）NATIVE_TOOLS 的合法规划调用：
     *     不把"核心动作未发生"等同于"这轮禁止工具"，调用进入原恢复校验与执行链，
     *     按提案流程受理；真实规划写入不发生（替身工具零执行）。旧实现在到达
     *     权限/来源模式校验之前就按收尾协议否决整批。
     */
    @Test
    void legacyNativePlanningCallEntersRecoveryChain() {
        Fixture fixture = fixture("请生成迭代规划草稿");
        AgentRunView run = baseStateWithToolEvidence(fixture, 2);
        ModelTurnResult response = new ModelTurnResult("开始生成规划",
                List.of(planningCall("call-plan-1")), ModelFinishReason.TOOL_CALLS,
                null, "OPENAI_COMPATIBLE", "model-a", 120L);
        // 旧记录：不携带意图元数据；来源 NATIVE_TOOLS；调用尚未执行
        AgentRunView saved = saveResponseForTakeover(run, response, null);
        var approval = mock(com.shitulelv.aicollab.agent.application.view.AgentApprovalView.class);
        when(approval.id()).thenReturn(UUID.randomUUID());
        when(approval.revision()).thenReturn(1);
        when(approval.proposalFamily()).thenReturn(com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily.TASK_CREATE);
        when(approvals.proposeOrRevise(any(), any(), any(), any(), any())).thenReturn(
                new com.shitulelv.aicollab.agent.application.AgentProposalOutcome(approval,
                        com.shitulelv.aicollab.agent.application.AgentProposalOutcome.Operation.CREATED,
                        null, json.createObjectNode().put("goal", "生成本迭代规划草稿"),
                        json.createObjectNode()));

        AgentWorkerOutcome takeover = realTakeoverAdvance(fixture, saved);

        assertThat(takeover.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        // 提案已受理但真实规划写入未发生（等待人工审批），替身工具零执行
        assertThat(executedToolCalls).isEmpty();
        verify(approvals, times(1)).proposeOrRevise(any(), any(), any(), any(), any());
        assertThat(jdbc.queryForObject("""
                SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-plan-1'
                """, String.class, saved.id())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT result_json->>'effect' FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='call-plan-1'
                """, String.class, saved.id())).isEqualTo("PROPOSAL_PENDING");
        assertThat(assistantMessages(saved.sessionId())).hasSize(1);
    }

    /**
     * 22. 旧元数据 LEGACY_READ_ONLY 的写调用仍拒绝：来源模式校验在恢复链内生效。
     */
    @Test
    void legacyReadOnlyWriteCallStillRejected() {
        Fixture fixture = fixture("请生成迭代规划草稿");
        AgentRunView run = baseStateWithToolEvidence(fixture, 2);
        ModelTurnResult response = new ModelTurnResult("开始生成规划",
                List.of(planningCall("call-plan-2")), ModelFinishReason.TOOL_CALLS,
                null, "OPENAI_COMPATIBLE", "model-a", 120L);
        AgentRunView saved = saveResponseForTakeover(run, response, null, "LEGACY_READ_ONLY");

        AgentWorkerOutcome takeover = realTakeoverAdvance(fixture, saved);

        assertThat(takeover.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(takeover.errorCode()).isEqualTo("LEGACY_WRITE_TOOL_FORBIDDEN");
        assertThat(executedToolCalls).isEmpty();
        verify(approvals, never()).proposeOrRevise(any(), any(), any(), any(), any());
        assertThat(runStatus(saved.id())).isEqualTo("FAILED");
    }

    /**
     * 23. 显式持久化 finalizing=true 的工具响应仍按协议违规拒绝（回退只作用于缺元数据的旧记录）。
     */
    @Test
    void explicitFinalizingToolResponseStillRejected() {
        Fixture fixture = fixture("汇总这周进展");
        AgentRunView run = baseStateWithToolEvidence(fixture, 2);
        ModelTurnResult response = new ModelTurnResult("收尾轮却返回调用",
                List.of(toolCall("call-veto", "q-veto")), ModelFinishReason.TOOL_CALLS,
                null, "OPENAI_COMPATIBLE", "model-a", 120L);
        AgentRunView saved = saveResponseForTakeover(run, response, true);

        AgentWorkerOutcome takeover = realTakeoverAdvance(fixture, saved);

        assertThat(takeover.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(executedToolCalls).isEmpty();
        assertThat(skippedInvocationCount(saved.id())).isEqualTo(1);
        assertThat(runStatus(saved.id())).isEqualTo("BUDGET_EXCEEDED");
    }

    // ---------- 工具批次补修的现场构造 ----------

    private ModelToolCall toolCall(String id, String query) {
        return new ModelToolCall(id, "list_tasks", json.createObjectNode().put("query", query));
    }

    private ModelToolCall planningCall(String id) {
        return new ModelToolCall(id, "start_task_plan",
                json.createObjectNode().put("goal", "生成本迭代规划草稿"));
    }

    private com.fasterxml.jackson.databind.JsonNode successToolResult() {
        return json.createObjectNode().put("status", "SUCCEEDED");
    }

    /** 用真实工具执行器协调器接管已过期运行并推进一次。 */
    private AgentWorkerOutcome realTakeoverAdvance(Fixture fixture, AgentRunView saved) {
        recovery.claim("worker-tool-recovery", Duration.ofMinutes(6)).orElseThrow();
        return realToolCoordinator.advance(reload(fixture, saved));
    }

    private int toolCallsUsed(UUID runId) {
        return jdbc.queryForObject("SELECT tool_calls_used FROM agent_run WHERE id=?", Integer.class, runId);
    }

    private java.util.List<String> invocationStatuses(UUID runId) {
        return jdbc.queryForList(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? ORDER BY turn_sequence,ordinal",
                String.class, runId);
    }

    private int skippedInvocationCount(UUID runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_tool_invocation WHERE run_id=? AND status='SKIPPED'",
                Integer.class, runId);
    }

    // ---------- 配对回归的现场构造 ----------


    /** 让正常路径真实出站一次请求：配置解析与模型调用都指向受控替身。 */
    private void modelReturns(ModelTurnResult response) {
        when(modelExecutor.resolveRequest(any())).thenReturn(AgentRuntimeBehaviorTest.resolved(true));
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any())).thenReturn(response);
    }

    private ModelTurnResult textTurnWithUsage(String content, ModelUsage usage) {
        return new ModelTurnResult(content, List.of(), ModelFinishReason.STOP, usage,
                "OPENAI_COMPATIBLE", "model-a", 120L);
    }

    /** 断言请求前的收敛判定（保证场景确实落在目标边界上，而不是巧合通过）。 */
    private void assertDecision(Fixture fixture, AgentRunView run, AgentConvergencePolicy.Mode expected) {
        var decision = new AgentConvergencePolicy().decide(reload(fixture, run),
                AgentRuntimeLimits.forSkill("ITERATION_PLANNING"),
                repository.listSteps(fixture.project(), run.id()));
        assertThat(decision.mode()).isEqualTo(expected);
    }

    /** 构造请求前状态：每轮一次成功的 list_tasks 查询（轮次与工具结果均经真实仓储落库）。 */
    private AgentRunView baseStateWithToolEvidence(Fixture fixture, int pairs) {
        return baseStateWithToolEvidence(fixture, java.util.Arrays.stream(new int[pairs]).map(i -> 1).toArray());
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
        // 请求前状态的既有轮次都已在各自的 advance 中被消费（生产不变式），
        // 否则 5b 会把最后一个基础轮次误当成待恢复响应
        jdbc.update("""
                UPDATE agent_step SET output_json=jsonb_set(COALESCE(output_json,'{}'::jsonb),'{batchHandled}','true'::jsonb)
                WHERE run_id=? AND type='MODEL_TURN'
                """, run.id());
        return run;
    }

    /**
     * 把响应按生产写入路径落库（含持久化收尾意图，与响应/用量同一事务），再还原成
     * "MODEL_TURN 已提交、最终收尾尚未提交"的崩溃现场：清除消费标记并使租约过期。
     */
    private AgentRunView saveResponseForTakeover(AgentRunView run, ModelTurnResult response, Boolean finalizingIntent) {
        return saveResponseForTakeover(run, response, finalizingIntent, "NATIVE_TOOLS");
    }

    private AgentRunView saveResponseForTakeover(AgentRunView run, ModelTurnResult response,
            Boolean finalizingIntent, String sourceMode) {
        String callId = repository.beginModelCall(run.projectId(), run.id(), "MODEL_TURN");
        var settlement = AgentRunEventRecorder.UsageSettlement.fromRaw(
                response.usage(), 10, 10, response.latencyMs());
        AgentRunView saved = repository.recordModelTurnWithSettlement(run, response, callId, "MODEL_TURN",
                settlement, sourceMode, finalizingIntent);
        clearConsumption(saved.id());
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '5 seconds' WHERE id=?", saved.id());
        return saved;
    }

    /** 接管已过期运行并推进一次（等价于新 worker 领取后 advance）。 */
    private AgentWorkerOutcome takeoverAdvance(Fixture fixture, AgentRunView saved) {
        recovery.claim("worker-takeover", Duration.ofMinutes(6)).orElseThrow();
        return pairCoordinator.advance(reload(fixture, saved));
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

    private String runStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM agent_run WHERE id=?", String.class, runId);
    }

    private int modelTurnCount(UUID runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE run_id=? AND type='MODEL_TURN'", Integer.class, runId);
    }

    private Integer runActual(UUID runId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM agent_run WHERE id=?", Integer.class, runId);
    }

    private int settlementCount(UUID runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_usage_settlement WHERE run_id=?", Integer.class, runId);
    }

    // ========== 现场构造与测试装配 ==========

    private record CrashWindow(ClaimedAgentRun claim, AgentRunView run) {
    }

    /** 构造"MODEL_TURN 已提交、最终收尾尚未提交"的现场：RUNNING + 未消费文本轮次。 */
    private CrashWindow crashWindowWithPlainText(Fixture fixture, String content) {
        AgentRunView run = runningRun(fixture);
        run = repository.recordModelTurn(run, plainTextTurn(content));
        clearConsumption(run.id());
        return new CrashWindow(claimOf(run), reload(fixture, run));
    }

    private void clearConsumption(UUID runId) {
        jdbc.update("""
                UPDATE agent_step SET output_json=output_json-'batchHandled'
                WHERE run_id=? AND type='MODEL_TURN'
                """, runId);
    }

    private ClaimedAgentRun claimOf(AgentRunView run) {
        return new ClaimedAgentRun(run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                run.parentRunId(), run.role(), run.depth(), run.goal(), run.status(),
                run.scheduled(), run.correctionAttempted(), run.version());
    }

    private int countSteps(UUID runId, String type) {
        return type == null
                ? jdbc.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=?", Integer.class, runId)
                : jdbc.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=? AND type=?",
                        Integer.class, runId, type);
    }

    private int countAssistantMessages(UUID sessionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_message WHERE session_id=? AND role='ASSISTANT'",
                Integer.class, sessionId);
    }

    private List<String> assistantMessages(UUID sessionId) {
        return jdbc.queryForList(
                "SELECT content FROM agent_message WHERE session_id=? AND role='ASSISTANT' ORDER BY created_at",
                String.class, sessionId);
    }

    private ModelTurnResult plainTextTurn(String content) {
        return new ModelTurnResult(content, List.of(), ModelFinishReason.STOP,
                null, "OPENAI_COMPATIBLE", "model-a", 120L);
    }

    private ModelTurnResult toolCallTurn(String narration, String callId) {
        return new ModelTurnResult(narration,
                List.of(new ModelToolCall(callId, "list_tasks", json.createObjectNode())),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 120L);
    }

    private com.fasterxml.jackson.databind.JsonNode toolInput(String callId) {
        var input = json.createObjectNode();
        input.put("toolCallId", callId);
        input.set("arguments", json.createObjectNode());
        return input;
    }

    /** 与真实调用一致的输入形状：arguments 必须与 invocation 落库值一致才能按 call_id 关联更新。 */
    private com.fasterxml.jackson.databind.JsonNode toolInput(ModelToolCall call) {
        var input = json.createObjectNode();
        input.put("toolCallId", call.id());
        input.set("arguments", call.arguments());
        return input;
    }

    private AgentRunView reload(Fixture fixture, AgentRunView run) {
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    private AgentRuntimeCoordinator coordinator(AgentToolCallExecutor executor) {
        return coordinator(executor, new AgentContextProperties(false, 20_000, 40_000, 4_000, java.util.Map.of()));
    }

    private AgentRuntimeCoordinator coordinator(AgentToolCallExecutor executor, AgentContextProperties properties) {
        return coordinator(executor, properties, new AgentToolRegistry(java.util.List.of()));
    }

    private AgentRuntimeCoordinator coordinator(AgentToolCallExecutor executor, AgentContextProperties properties,
            AgentToolRegistry toolRegistry) {
        var cancellation = new AgentCancellationService(repository);
        var registry = new AgentSkillRegistry();
        return new AgentRuntimeCoordinator(
                repository, assembler, registry, new AgentPlanService(repository, json),
                toolRegistry, cancellation, modelExecutor,
                new AgentConvergencePolicy(), json, events, properties,
                new AgentModelMessageComposer(repository, mock(AgentMemoryService.class), json), executor,
                new AgentContextSummarizer(repository, modelExecutor, json));
    }

    private AgentRunView runningRun(Fixture fixture) {
        var session = repository.createSession(fixture.project(), fixture.user(), "结果恢复验收");
        var run = repository.createRun(fixture.project(), session.id(), fixture.user(),
                fixture.goal(), false, "ITERATION_PLANNING", null);
        recovery.claim("worker-1", Duration.ofMinutes(6)).orElseThrow();
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    private com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext context(AgentRunView run) {
        return new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(), "SUPERVISOR", false,
                AgentPageContext.empty(), AgentRuntimeLimits.forSkill(run.skillCode()), 0, List.of());
    }

    private record Fixture(UUID user, UUID project, String goal) {
    }

    private Fixture fixture(String goal) {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "recover-" + user.toString().substring(0, 8), "test-only-hash", "Recover");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "结果恢复验收", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        return new Fixture(user, project, goal);
    }
}
