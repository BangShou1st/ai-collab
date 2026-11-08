package com.shitulelv.aicollab.agent.evals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.application.runtime.AgentBudgetProperties;
import com.shitulelv.aicollab.agent.application.runtime.AgentCancellationService;
import com.shitulelv.aicollab.agent.application.runtime.AgentContextAssembler;
import com.shitulelv.aicollab.agent.application.runtime.AgentContextProperties;
import com.shitulelv.aicollab.agent.application.runtime.AgentContextSummarizer;
import com.shitulelv.aicollab.agent.application.runtime.AgentConvergencePolicy;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventStreamService;
import com.shitulelv.aicollab.agent.application.runtime.AgentLoopGuard;
import com.shitulelv.aicollab.agent.application.runtime.AgentModelMessageComposer;
import com.shitulelv.aicollab.agent.application.runtime.AgentPlanService;
import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.application.runtime.AgentToolCallExecutor;
import com.shitulelv.aicollab.agent.application.runtime.AgentToolScheduler;
import com.shitulelv.aicollab.agent.application.runtime.RoutingAgentModelExecutor;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.agent.infrastructure.tool.CreateMemoryApprovalAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.DocumentResearchDelegateAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.KnowledgeSearchAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.ProjectQuestionAgentTool;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import jakarta.validation.Validation;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Agent 评估运行器：Testcontainers PostgreSQL + 脚本化模型网关 + 真实仓储/协调器全链路执行
 * golden case，再从持久化事实（agent_run_event 事件流、DB 副作用、invocation 状态）机械化断言。
 *
 * <p><b>为什么自建轻量运行器而不是接外部评估框架</b>：本评估集的核心价值是"断言可从项目内
 * 持久化事实机械化判定"（事件序列、审批行状态、台账判重、预算记账）。外部评估框架的断言面
 * 是模型输出文本，接不进项目内的事件与数据库边界；自建运行器复用既有 Postgres 测试的装配
 * 模式（mock 模型 + 真实仓储），代码量小且与生产执行路径完全同构。</p>
 *
 * <p>执行模型与 {@code AgentDelegationPostgresTest} 等全链路回归一致：mock
 * {@link RoutingAgentModelExecutor} 按 case 脚本吐确定性响应，其余组件全部走生产实现
 * （AgentRepository / AgentRuntimeCoordinator / AgentToolCallExecutor / AgentApprovalService /
 * AgentLoopGuard / 委派台账），事件经真实 {@link AgentEventService} 持久化到
 * {@code agent_run_event}，供断言机械化读取。</p>
 */
public final class AgentEvalRunner {

    // ---------- 结果模型 ----------

    /** 单条断言明细：通过/失败 + 定位信息。 */
    public record AssertionDetail(String name, boolean passed, String message) {}

    /** 单个 case 的结构化结果：报告与回归对比的基本单位。 */
    public record CaseResult(String caseId, String category, String guards, boolean passed,
            String finalStatus, List<String> failureReasons, List<AssertionDetail> details,
            long durationMs) {}

    // ---------- 根运行到达其一即停止推进 ----------

    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "SUCCEEDED", "FAILED", "CANCELED", "PAUSED", "BUDGET_EXCEEDED",
            "WAITING_FOR_USER_INPUT", "FAILED_RETRYABLE");

    private static final String EVAL_WORKER = "eval-worker";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbc;
    private final AgentRepository repository;
    private final AgentRecoveryJob recovery;
    private final AgentEventService events;
    private final RoutingAgentModelExecutor modelExecutor;

    // 每个 case 的脚本游标与观测状态（Mockito Answer 闭包读取，case 串行执行无并发竞争）
    private GoldenCase currentCase;
    private int rootTurnCursor;
    private int childTurnCursor;
    private boolean listTasksShouldFail;
    private final List<String> rootRequestDumps = new CopyOnWriteArrayList<>();
    private AgentRuntimeCoordinator currentCoordinator;
    private AgentApprovalService currentApprovalService;
    private UUID currentRootRunId;

    public AgentEvalRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 真实事件服务：事件流必须持久化到 agent_run_event，断言才有机械化事实可读。
        // 流发布器是纯内存副作用，测试装配用 mock 替换。
        this.events = new AgentEventService(
                new AgentEventRepository(jdbc, json), Mockito.mock(AgentEventStreamService.class));
        var recorder = new AgentRunEventRecorder(jdbc, json);
        recorder.setEvents(events);
        this.repository = new AgentRepository(jdbc, json, recorder);
        this.recovery = new AgentRecoveryJob(repository);
        this.modelExecutor = Mockito.mock(RoutingAgentModelExecutor.class);
    }

    // ========== 对外入口 ==========

    /** 执行单个 golden case 并给出结构化判定结果。case 之间互不影响（每 case 清库重建）。 */
    public CaseResult run(GoldenCase c) {
        long start = System.nanoTime();
        List<AssertionDetail> details = new ArrayList<>();
        Fixture fixture = null;
        UUID rootRunId = null;
        try {
            currentCase = c;
            rootTurnCursor = 0;
            childTurnCursor = 0;
            rootRequestDumps.clear();
            listTasksShouldFail = c.toolBehavior() != null
                    && "FAIL".equals(c.toolBehavior().listTasks().get("list_tasks"));
            cleanDatabase();
            fixture = seedFixture();
            rootRunId = createSeededRun(c, fixture);
            stubModelGateway(c);
            // 防御性归零：即使 stubbing 过程意外触发了 Answer，也不把游标带进真实执行
            rootTurnCursor = 0;
            childTurnCursor = 0;
            buildCoordinator(c);
            // 领取后进入 RUNNING，再交给协调器推进
            recovery.claim(EVAL_WORKER, Duration.ofMinutes(6)).orElseThrow();
            AgentRunView view = repository.findRun(fixture.project(), rootRunId).orElseThrow();
            currentCoordinator.advance(view);
            executeRunLoop(fixture, rootRunId);
            applyPostRun(fixture, rootRunId, c);
            evaluate(c, fixture, rootRunId, details);
        } catch (RuntimeException failure) {
            // 框架级异常（脚本耗尽、断言探测抛错等）也计入结果，不让单 case 崩掉整个套件；
            // 同时附上事件流与 invocation 现场快照，失败明细足够定位
            details.add(new AssertionDetail("执行未抛出框架级异常", false,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage()
                            + "；事件现场=" + dumpEvents(rootRunId)
                            + "；invocation 现场=" + dumpInvocations(rootRunId)));
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        List<String> failures = details.stream()
                .filter(d -> !d.passed()).map(AssertionDetail::message).toList();
        return new CaseResult(c.id(), c.category().name(), c.guards(), failures.isEmpty(),
                runStatusOf(rootRunId), List.copyOf(failures), List.copyOf(details), durationMs);
    }

    // ========== 场景准备 ==========

    private record Fixture(UUID user, UUID project, UUID session) {}

    private void cleanDatabase() {
        // 与既有 Postgres 回归测试一致的清理顺序（外键级联自上而下）
        jdbc.update("DELETE FROM agent_delegation_ledger");
        jdbc.update("DELETE FROM agent_planning_operation_event");
        jdbc.update("DELETE FROM agent_planning_operation");
        jdbc.update("DELETE FROM agent_usage_settlement");
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_approval");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_memory");
        jdbc.update("DELETE FROM project_task");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
    }

    private Fixture seedFixture() {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "eval-" + user.toString().substring(0, 8), "test-only-hash", "EvalUser");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "agent-eval-golden-set", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        var session = repository.createSession(project, user, "agent-eval-golden-set");
        return new Fixture(user, project, session.id());
    }

    /** 创建根运行并按 case 覆盖预算/推进步、预置 seed 数据。 */
    private UUID createSeededRun(GoldenCase c, Fixture fixture) {
        GoldenCase.Seed seed = c.seed();
        if (seed != null) {
            for (GoldenCase.Seed.SeedTask task : seed.tasks()) {
                jdbc.update("""
                        INSERT INTO project_task(id,project_id,title,status,created_by)
                        VALUES (gen_random_uuid(),?,?,?,?)
                        """, fixture.project(), task.title(), task.status(), fixture.user());
            }
        }
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                c.userRequest(), false, c.skillCode(), null);
        UUID runId = run.id();
        applyRunOverrides(c, runId);
        seedSteps(c, runId);
        return runId;
    }

    private void applyRunOverrides(GoldenCase c, UUID runId) {
        GoldenCase.RunOverrides overrides = c.runOverrides();
        if (overrides != null) {
            if (overrides.contextPolicyVersion() != null) {
                jdbc.update("UPDATE agent_run SET context_policy_version=? WHERE id=?",
                        overrides.contextPolicyVersion(), runId);
            }
            if (overrides.maxInputTokens() != null) {
                jdbc.update("UPDATE agent_run SET max_input_tokens=? WHERE id=?",
                        overrides.maxInputTokens(), runId);
            }
            if (Boolean.TRUE.equals(overrides.useAllButOneStep())) {
                // 推进步耗到只剩 1（< 收尾所需 2），下一轮收敛判定直接 EXHAUSTED
                jdbc.update("UPDATE agent_run SET steps_used=max_steps-1 WHERE id=?", runId);
            }
            if (overrides.seedInputTokensActual() != null) {
                jdbc.update("UPDATE agent_run SET input_tokens_actual=? WHERE id=?",
                        overrides.seedInputTokensActual(), runId);
            }
        }
        GoldenCase.Budget budget = c.budget();
        if (budget != null && budget.seedInputTokensActual() != null) {
            jdbc.update("UPDATE agent_run SET input_tokens_actual=? WHERE id=?",
                    budget.seedInputTokensActual(), runId);
        }
    }

    private void seedSteps(GoldenCase c, UUID runId) {
        List<GoldenCase.SeedStep> steps = c.seedSteps();
        if (steps == null) return;
        int sequence = 1;
        for (GoldenCase.SeedStep step : steps) {
            jdbc.update("""
                    INSERT INTO agent_step(run_id,sequence_no,type,tool_name,output_json,reason)
                    VALUES (?,?,?,?,?::jsonb,?)
                    """, runId, sequence++, step.type(), step.toolName(),
                    json.valueToTree(step.output()).toString(), step.reason());
        }
    }

    // ========== 脚本化模型网关 ==========

    private void stubModelGateway(GoldenCase c) {
        // 关键：重打桩前先 reset。Mockito 的 when(mock.callModel(...)) 会先执行当前生效的
        // Answer 一次，若上一个 case 的 Answer 仍在，会把脚本游标消耗一格（turn1 被吞），
        // 造成"第二个 case 的第一个工具轮凭空消失"的跨 case 干扰。
        Mockito.reset(modelExecutor);
        Mockito.when(modelExecutor.resolveRequest(Mockito.any()))
                .thenReturn(resolvedNativeTools());
        Mockito.lenient().when(modelExecutor.isLegacyModeForRun(Mockito.any())).thenReturn(false);
        Mockito.when(modelExecutor.callModel(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyBoolean(), Mockito.any())).thenAnswer(invocation -> {
            AgentRunView run = invocation.getArgument(0);
            List<ModelMessage> messages = invocation.getArgument(1);
            if (run != null && run.depth() > 0) {
                return childTurn(c);
            }
            // 根运行：记录请求全文（预算提示注入等断言的事实来源），按脚本吐下一轮
            rootRequestDumps.add(String.valueOf(messages));
            return rootTurn(c);
        });
        Mockito.lenient().when(modelExecutor.callModelWithoutTools(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(plainTurn("仍有效约束：保留原目标，按已有证据回答。"));
    }

    /** 原生工具调用模式的请求快照（与既有 Postgres 回归测试的 resolved(true) 同构）。 */
    private static RoutingAgentModelExecutor.ResolvedRequest resolvedNativeTools() {
        var capabilities = java.util.EnumSet.of(
                com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability.NATIVE_TOOLS,
                com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability.CHAT);
        var now = java.time.OffsetDateTime.now();
        String modelName = "eval-native";
        var provider = new com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider(
                UUID.randomUUID(), UUID.randomUUID(), modelName,
                com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.invalid", "/v1/chat/completions", "encrypted", modelName, true,
                0.2, 1024, capabilities, true, now, now, null);
        return RoutingAgentModelExecutor.ResolvedRequest.of(provider, provider.toModelConfiguration());
    }

    private ModelTurnResult rootTurn(GoldenCase c) {
        List<GoldenCase.ScriptTurn> turns = c.script().rootTurns();
        if (rootTurnCursor < turns.size()) {
            return toTurnResult(turns.get(rootTurnCursor++));
        }
        // 脚本耗尽回退：真实 LLM 不会无限重复，这里回退为文本收尾，让"脚本本应被
        // 提前拦截/收口"的 case 用断言暴露差异（如 loop guard 失效时 finalStatus 变 SUCCEEDED）
        return plainTurn("（脚本已耗尽）基于现有信息回答。");
    }

    private ModelTurnResult childTurn(GoldenCase c) {
        List<GoldenCase.ScriptTurn> turns = c.script().childTurns();
        if (childTurnCursor < turns.size()) {
            return toTurnResult(turns.get(childTurnCursor++));
        }
        return plainTurn("子研究完成：已检索全部相关文档，未发现更多有效信息。");
    }

    private ModelTurnResult toTurnResult(GoldenCase.ScriptTurn turn) {
        List<ModelToolCall> calls = new ArrayList<>();
        if (turn.toolCalls() != null) {
            for (GoldenCase.ScriptTurn.ScriptToolCall call : turn.toolCalls()) {
                calls.add(new ModelToolCall("eval-" + UUID.randomUUID(), call.name(),
                        json.valueToTree(call.arguments())));
            }
        }
        return new ModelTurnResult(turn.content(), calls,
                calls.isEmpty() ? ModelFinishReason.STOP : ModelFinishReason.TOOL_CALLS,
                null, "OPENAI_COMPATIBLE", "eval-scripted", 50L);
    }

    private ModelTurnResult plainTurn(String content) {
        return new ModelTurnResult(content, List.of(), ModelFinishReason.STOP,
                null, "OPENAI_COMPATIBLE", "eval-scripted", 50L);
    }

    // ========== 装配（与 AgentDelegationPostgresTest 的全链路装配同构） ==========

    private void buildCoordinator(GoldenCase c) {
        ProjectAccessGuard access = Mockito.mock(ProjectAccessGuard.class);
        Mockito.lenient().when(access.requireMember(Mockito.any(), Mockito.any()))
                .thenReturn(ProjectRole.OWNER);
        // requireAdmin 为 void mock 默认放行：评估关注 agent 行为边界，不重复测权限组件

        DocumentSearchService search = Mockito.mock(DocumentSearchService.class);
        Mockito.lenient().when(search.resolveUserDocumentScope(Mockito.any(), Mockito.any()))
                .thenReturn(DocumentSearchService.UserDocumentScope.unrestricted());
        Mockito.lenient().when(search.search(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyInt())).thenReturn(List.of());

        KnowledgeSearchAgentTool knowledgeTool = new KnowledgeSearchAgentTool(
                access, search, new KnowledgeContextBuilder(), json);
        ProjectQuestionAgentTool questionAlias = new ProjectQuestionAgentTool(knowledgeTool);
        DocumentResearchDelegateAgentTool delegateTool = new DocumentResearchDelegateAgentTool(json);
        EvalTaskListTool listTool = new EvalTaskListTool(json, () -> listTasksShouldFail);
        EvalTaskWriteApprovalTool taskWriteTool = new EvalTaskWriteApprovalTool(jdbc, json);
        AgentMemoryService memoryService = new AgentMemoryService(new AgentMemoryRepository(jdbc), access);
        CreateMemoryApprovalAgentTool memoryWriteTool = new CreateMemoryApprovalAgentTool(json,
                Validation.buildDefaultValidatorFactory().getValidator(), memoryService, access);

        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                listTool, knowledgeTool, questionAlias, delegateTool, taskWriteTool, memoryWriteTool));

        // 真实审批服务（提案→批准→落库全链路的事实来源）；提案受理边界需要 jdbc 做行锁与暂停检查
        currentApprovalService = new AgentApprovalService(
                new AgentApprovalRepository(jdbc, json), registry, access, json,
                java.time.Clock.systemDefaultZone(), events,
                Mockito.mock(com.shitulelv.aicollab.agent.application.AgentHistoryVisibilityService.class));
        ReflectionTestUtils.invokeMethod(currentApprovalService, "configureInvocations", jdbc);

        var executor = new AgentToolCallExecutor(repository, registry,
                new AgentCancellationService(repository), new AgentLoopGuard(), currentApprovalService,
                modelExecutor, new AgentToolResultSanitizer(json), json, events, new AgentToolScheduler());

        long softCap = c.budget() != null && c.budget().softInputTokenCap() != null
                ? c.budget().softInputTokenCap() : AgentBudgetProperties.defaults().softInputTokenCap();
        currentCoordinator = new AgentRuntimeCoordinator(
                repository,
                assembler(),
                new AgentSkillRegistry(),
                new AgentPlanService(repository, json),
                registry,
                new AgentCancellationService(repository),
                modelExecutor,
                new AgentConvergencePolicy(),
                json,
                events,
                new AgentContextProperties(true, 20_000, 4_000, 2_000, Map.of()),
                new AgentBudgetProperties(softCap, AgentBudgetProperties.defaults().sessionAggregateTokenCap()),
                null,
                new AgentModelMessageComposer(repository, Mockito.mock(AgentMemoryService.class), json),
                executor,
                new AgentContextSummarizer(repository, modelExecutor, json));
    }

    /** 上下文组装替身：与运行视图推导一致的身份上下文（子运行用默认限额）。 */
    private AgentContextAssembler assembler() {
        var assembler = Mockito.mock(AgentContextAssembler.class);
        Mockito.when(assembler.assemble(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> {
                    AgentRunView run = invocation.getArgument(0);
                    if (run == null) return null;
                    return new AgentExecutionContext(
                            run.id(), run.sessionId(), run.projectId(), run.requesterId(), "OWNER", false,
                            AgentPageContext.empty(),
                            run.depth() > 0 ? AgentRuntimeLimits.defaults()
                                    : AgentRuntimeLimits.forSkill(run.skillCode()),
                            run.depth(), List.of());
                });
        return assembler;
    }

    // ========== 运行推进循环 ==========

    private void executeRunLoop(Fixture fixture, UUID rootRunId) {
        GoldenCase.Script script = currentCase.script();
        for (int advance = 0; advance < script.maxAdvances(); advance++) {
            Optional<ClaimedAgentRun> claimed = recovery.claim(EVAL_WORKER, Duration.ofMinutes(6));
            if (claimed.isEmpty()) return;
            AgentRunView view = repository.findRun(fixture.project(), claimed.get().id()).orElseThrow();
            currentCoordinator.advance(view);
            String status = runStatusOf(rootRunId);
            if (TERMINAL_STATUSES.contains(status)) return;
        }
        throw new IllegalStateException(
                "推进循环超过 maxAdvances=" + script.maxAdvances() + "，根运行仍未到达终态");
    }

    /** 运行结束后的模拟人工动作：批准或拒绝提案（审批链路的"人"一侧）。 */
    private void applyPostRun(Fixture fixture, UUID rootRunId, GoldenCase c) {
        GoldenCase.PostRun postRun = c.postRun();
        if (postRun == null) return;
        UUID approvalId = jdbc.queryForObject(
                "SELECT id FROM agent_approval WHERE run_id=? ORDER BY created_at LIMIT 1",
                UUID.class, rootRunId);
        if ("APPROVE".equals(postRun.action())) {
            // nonce 与提案创建侧一致（nonce_hash = sha256(approvalId)），等价真实审批凭证
            currentApprovalService.approve(fixture.project(), approvalId, fixture.user(),
                    approvalId.toString(), UUID.randomUUID());
        } else {
            currentApprovalService.reject(fixture.project(), approvalId, fixture.user(),
                    approvalId.toString(), UUID.randomUUID(), postRun.reason());
        }
    }

    // ========== 断言评估（全部机械化，读持久化事实） ==========

    private void evaluate(GoldenCase c, Fixture fixture, UUID runId, List<AssertionDetail> details) {
        GoldenCase.Assertions a = c.assertions();
        UUID projectId = fixture.project();
        UUID sessionId = fixture.session();

        // 0. 现场诊断（始终通过，仅供失败定位：模型轮次/步骤/脚本消费轨迹）
        details.add(new AssertionDetail("现场诊断", true, diagnose(runId)));

        // 1. 最终运行状态
        if (a.finalStatus() != null) {
            String actual = runStatusOf(runId);
            details.add(new AssertionDetail("finalStatus=" + a.finalStatus(),
                    a.finalStatus().equals(actual), "期望 " + a.finalStatus() + "，实际 " + actual));
        }

        // 2. 事件流断言（agent_run_event）
        List<Map<String, Object>> rootEvents = jdbc.queryForList(
                "SELECT type, payload_json::text AS payload FROM agent_run_event WHERE run_id=?", runId);
        Set<String> seenEventTypes = new HashSet<>();
        rootEvents.forEach(e -> seenEventTypes.add((String) e.get("type")));
        for (GoldenCase.EventAssertion ea : a.events()) {
            long count = rootEvents.stream().filter(e -> ea.type().equals(e.get("type"))).count();
            boolean minOk = count >= (ea.min() == null ? 1 : ea.min());
            boolean maxOk = ea.max() == null || count <= ea.max();
            boolean payloadOk = true;
            String payloadNote = "";
            if (ea.payloadContains() != null) {
                payloadOk = rootEvents.stream()
                        .filter(e -> ea.type().equals(e.get("type")))
                        .anyMatch(e -> String.valueOf(e.get("payload")).contains(ea.payloadContains()));
                payloadNote = "，载荷须含 '" + ea.payloadContains() + "'";
            }
            details.add(new AssertionDetail(
                    "event " + ea.type() + " count=" + count + payloadNote,
                    minOk && maxOk && payloadOk,
                    "事件 " + ea.type() + " 实际 " + count + " 次" + payloadNote
                            + "；实际事件集 " + seenEventTypes));
        }
        for (String forbidden : a.forbiddenEvents()) {
            long count = rootEvents.stream().filter(e -> forbidden.equals(e.get("type"))).count();
            details.add(new AssertionDetail("forbidden event " + forbidden, count == 0,
                    "禁止出现的事件 " + forbidden + " 实际出现 " + count + " 次；实际事件集 " + seenEventTypes));
        }

        // 3. 工具调用断言（agent_tool_invocation）
        for (GoldenCase.ToolInvocationAssertion tia : a.toolInvocations()) {
            long count = jdbc.queryForObject("""
                    SELECT count(*) FROM agent_tool_invocation
                    WHERE run_id=? AND tool_name=? AND status=?
                    """, Long.class, runId, tia.tool(), tia.status());
            boolean ok = count >= (tia.min() == null ? 1 : tia.min())
                    && (tia.max() == null || count <= tia.max());
            details.add(new AssertionDetail(
                    "invocation " + tia.tool() + "[" + tia.status() + "] count=" + count, ok,
                    "工具 " + tia.tool() + " 状态 " + tia.status() + " 期望区间 ["
                            + (tia.min() == null ? 1 : tia.min()) + "," + (tia.max() == null ? "∞" : tia.max())
                            + "]，实际 " + count));
        }
        for (String tool : a.forbiddenToolExecuted()) {
            long count = jdbc.queryForObject("""
                    SELECT count(*) FROM agent_tool_invocation
                    WHERE run_id=? AND tool_name=? AND status='SUCCEEDED'
                    """, Long.class, runId, tool);
            details.add(new AssertionDetail("forbidden tool " + tool, count == 0,
                    "禁止执行的工具 " + tool + " 实际成功执行 " + count + " 次"));
        }
        for (String code : a.forbiddenErrorCodes()) {
            long count = jdbc.queryForObject("""
                    SELECT count(*) FROM agent_tool_invocation
                    WHERE run_id=? AND result_json->>'error'=?
                    """, Long.class, runId, code);
            details.add(new AssertionDetail("forbidden errorCode " + code, count == 0,
                    "禁止出现的错误码 " + code + " 实际出现 " + count + " 次"));
        }

        // 4. DB 副作用断言（受限 probe 词表）
        for (GoldenCase.DbAssertion da : a.db()) {
            long actual = probe(da, fixture, runId);
            boolean ok = actual >= da.min() && (da.max() == null || actual <= da.max());
            details.add(new AssertionDetail("db[" + da.name() + "] probe=" + da.probe()
                            + " count=" + actual, ok,
                    "DB 断言 '" + da.name() + "'（" + da.probe() + "）期望区间 [" + da.min() + ","
                            + (da.max() == null ? "∞" : da.max()) + "]，实际 " + actual));
        }

        // 5. 模型请求内容断言（软阈值提示注入等"提示必须出现在请求里"的守护）
        for (String text : a.modelRequestContains()) {
            boolean found = rootRequestDumps.stream().anyMatch(dump -> dump.contains(text));
            details.add(new AssertionDetail("modelRequestContains '" + text + "'", found,
                    "模型请求中未找到 '" + text + "'；捕获请求数 " + rootRequestDumps.size()));
        }

        // 6. 最终回答断言（根运行的 ASSISTANT 消息）
        if (!a.answerContains().isEmpty()) {
            String answer = rootAnswerOf(runId);
            for (String text : a.answerContains()) {
                details.add(new AssertionDetail("answerContains '" + text + "'",
                        answer != null && answer.contains(text),
                        "最终回答" + (answer == null ? "不存在" : "为: " + answer) + "，未包含 '" + text + "'"));
            }
        }
    }

    /** 执行受限 probe：只查 case 关心的聚合事实，不接受任意 SQL。 */
    private long probe(GoldenCase.DbAssertion da, Fixture fixture, UUID runId) {
        UUID projectId = fixture.project();
        UUID sessionId = fixture.session();
        return switch (da.probe()) {
            case "APPROVAL_STATUS" -> jdbc.queryForObject(
                    "SELECT count(*) FROM agent_approval WHERE run_id=? AND status=?",
                    Long.class, runId, da.expected());
            case "APPROVAL_COUNT" -> jdbc.queryForObject(
                    "SELECT count(*) FROM agent_approval WHERE run_id=?", Long.class, runId);
            case "TASK_COUNT" -> jdbc.queryForObject(
                    "SELECT count(*) FROM project_task WHERE project_id=?", Long.class, projectId);
            case "MEMORY_COUNT" -> jdbc.queryForObject(
                    "SELECT count(*) FROM agent_memory WHERE project_id=?", Long.class, projectId);
            case "MEMORY_CONTENT_CONTAINS" -> jdbc.queryForObject(
                    "SELECT count(*) FROM agent_memory WHERE project_id=? AND content LIKE ?",
                    Long.class, projectId, "%" + da.expected() + "%");
            case "CHILD_RUN_COUNT" -> jdbc.queryForObject(
                    "SELECT count(*) FROM agent_run WHERE parent_run_id=?", Long.class, runId);
            case "LEDGER_COUNT" -> jdbc.queryForObject(
                    "SELECT count(*) FROM agent_delegation_ledger WHERE session_id=?", Long.class, sessionId);
            default -> throw new IllegalStateException("未知 probe: " + da.probe());
        };
    }

    private String rootAnswerOf(UUID runId) {
        List<String> answers = jdbc.queryForList(
                "SELECT content FROM agent_message WHERE run_id=? AND role='ASSISTANT' ORDER BY created_at",
                String.class, runId);
        return answers.isEmpty() ? null : answers.get(answers.size() - 1);
    }

    /** 诊断快照：模型轮次、步骤、运行记账与脚本消费轨迹（定位失败现场用）。 */
    private String diagnose(UUID runId) {
        if (runId == null) return "(无运行)";
        try {
            String turns = jdbc.queryForList("""
                    SELECT sequence_no || ':' || COALESCE(tool_name, type) || ':' ||
                           COALESCE(LEFT(output_json::text, 120), '')
                    FROM agent_step WHERE run_id=? AND type='MODEL_TURN' ORDER BY sequence_no
                    """, String.class, runId).toString();
            String steps = jdbc.queryForList("""
                    SELECT sequence_no || ':' || type || ':' || COALESCE(tool_name, '') || ':' ||
                           COALESCE(LEFT(output_json::text, 80), '')
                    FROM agent_step WHERE run_id=? ORDER BY sequence_no
                    """, String.class, runId).toString();
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, steps_used, tool_calls_used, input_tokens_actual, error_code FROM agent_run WHERE id=?",
                    runId);
            return "run=" + row + "；模型轮=" + turns + "；步骤=" + steps
                    + "；脚本消费=" + rootRequestDumps.size() + " 次请求";
        } catch (RuntimeException e) {
            return "(诊断读取失败: " + e.getMessage() + ")";
        }
    }

    /** 诊断快照：事件类型序列（定位失败现场用）。 */
    private String dumpEvents(UUID runId) {
        if (runId == null) return "(无运行)";
        try {
            return jdbc.queryForList(
                    "SELECT type FROM agent_run_event WHERE run_id=? ORDER BY sequence_no",
                    String.class, runId).toString();
        } catch (RuntimeException e) {
            return "(读取失败: " + e.getMessage() + ")";
        }
    }

    /** 诊断快照：invocation 状态（定位失败现场用）。 */
    private String dumpInvocations(UUID runId) {
        if (runId == null) return "(无运行)";
        try {
            return jdbc.queryForList("""
                    SELECT tool_name || '=' || status || COALESCE(':' || (result_json->>'error'), '')
                    FROM agent_tool_invocation WHERE run_id=? ORDER BY turn_sequence, ordinal
                    """, String.class, runId).toString();
        } catch (RuntimeException e) {
            return "(读取失败: " + e.getMessage() + ")";
        }
    }

    private String runStatusOf(UUID runId) {
        if (runId == null) return "(未创建)";
        return jdbc.queryForObject("SELECT status FROM agent_run WHERE id=?", String.class, runId);
    }

    // ========== 内置评估工具替身 ==========

    /**
     * list_tasks 只读替身：确定性输出（含查询词，保证不同参数产生不同输出签名），
     * 支持按 case 注入 FAIL 行为（守护"失败如实落库、回答不虚构"）。
     */
    private static final class EvalTaskListTool implements AgentTool {
        private final ObjectMapper json;
        private final BooleanSupplier failBehavior;

        EvalTaskListTool(ObjectMapper json, BooleanSupplier failBehavior) {
            this.json = json;
            this.failBehavior = failBehavior;
        }

        @Override public String name() { return "list_tasks"; }
        @Override public boolean writesBusinessData() { return false; }

        @Override
        public AgentToolDefinition definition() {
            return AgentToolDefinition.openObject(name(), "查询项目任务（评估替身）", false);
        }

        @Override
        public AgentToolResult execute(AgentToolContext context, com.fasterxml.jackson.databind.JsonNode arguments) {
            if (failBehavior.getAsBoolean()) {
                return AgentToolResult.failed("EVAL_TASK_LIST_DOWN", "任务查询服务暂不可用（评估注入）", false);
            }
            var data = json.createObjectNode()
                    .put("status", "SUCCEEDED")
                    .put("query", arguments.path("query").asText());
            var tasks = data.putArray("tasks");
            tasks.addObject().put("title", "搭建部署流水线").put("status", "TODO");
            tasks.addObject().put("title", "评审接口文档").put("status", "IN_PROGRESS");
            return new AgentToolResult(data, List.of(), List.of());
        }
    }

    /**
     * 任务写审批替身：走真实审批链（提案→批准→revalidate→execute），
     * execute 直接向 project_task 落一行——让"批准后真实落库"有可断言的 DB 副作用。
     * 零生产代码改动：不依赖 work 模块应用服务，只复用审批协议本身。
     */
    private static final class EvalTaskWriteApprovalTool implements ApprovalWriteAgentTool {
        private final JdbcTemplate jdbc;
        private final ObjectMapper json;

        EvalTaskWriteApprovalTool(JdbcTemplate jdbc, ObjectMapper json) {
            this.jdbc = jdbc;
            this.json = json;
        }

        @Override public String name() { return "create_task_after_approval"; }
        @Override public boolean writesBusinessData() { return true; }
        @Override public AgentProposalFamily proposalFamily() { return AgentProposalFamily.TASK_CREATE; }

        @Override
        public AgentToolDefinition definition() {
            return AgentToolDefinition.fromJson(name(), "创建任务提案（评估替身）；必须经管理员批准后写入任务",
                    """
                    {"type":"object","additionalProperties":false,"required":["title"],
                     "properties":{"title":{"type":"string","minLength":1,"maxLength":160},
                       "priority":{"type":["string","null"],"enum":["LOW","MEDIUM","HIGH","URGENT",null]}}}
                    """, true);
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode normalize(
                AgentToolContext context, com.fasterxml.jackson.databind.JsonNode arguments) {
            String title = arguments.path("title").asText("");
            if (title.isBlank() || title.length() > 160) {
                throw new IllegalArgumentException("任务标题必须为 1-160 字符");
            }
            var node = json.createObjectNode().put("title", title.strip());
            node.put("priority", arguments.path("priority").asText("MEDIUM"));
            return node;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode diff(
                AgentToolContext context, com.fasterxml.jackson.databind.JsonNode normalizedArguments) {
            return json.valueToTree(Map.of("operation", "CREATE", "after", normalizedArguments));
        }

        @Override
        public void revalidate(AgentToolContext context, com.fasterxml.jackson.databind.JsonNode arguments) {
            // 执行前重校验：标题仍有效（真实工具还会校验项目与关联实体）
            if (arguments.path("title").asText("").isBlank()) {
                throw new IllegalArgumentException("重校验失败：任务标题为空");
            }
        }

        @Override
        public AgentToolResult execute(AgentToolContext context, com.fasterxml.jackson.databind.JsonNode arguments) {
            UUID taskId = jdbc.queryForObject("""
                    INSERT INTO project_task(id,project_id,title,status,priority,created_by)
                    VALUES (gen_random_uuid(),?,?, 'TODO', ?,?)
                    RETURNING id
                    """, UUID.class, context.projectId(), arguments.path("title").asText(),
                    arguments.path("priority").asText("MEDIUM"), context.userId());
            var data = json.createObjectNode().put("status", "SUCCEEDED").put("taskId", taskId.toString());
            return new AgentToolResult(data, List.of(), List.of());
        }
    }
}
