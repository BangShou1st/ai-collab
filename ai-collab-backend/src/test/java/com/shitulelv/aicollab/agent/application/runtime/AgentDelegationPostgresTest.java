package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentWorker;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.agent.infrastructure.tool.DocumentResearchDelegateAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.KnowledgeSearchAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.ProjectQuestionAgentTool;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * document_research 只读委派链路回归（真实 PostgreSQL）：
 * 委派受理（子运行创建、预算切分、幂等）、父运行等待与唤醒、
 * 子运行结果回收与父运行综合、暂停边界。
 * 不依赖真实模型：模型行为由 mock 注入确定性响应序列。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentDelegationPostgresTest {
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
    private AgentRecoveryJob recovery;
    private AgentWorker worker;
    private final List<String> modelRequests = new CopyOnWriteArrayList<>();
    /** 每次真实模型请求实际暴露的工具名（可见性断言用：拒绝后不得再暴露已不可能受理的委派工具）。 */
    private final List<List<String>> exposedToolNames = new CopyOnWriteArrayList<>();
    private final Queue<ModelTurnResult> parentResponses = new ConcurrentLinkedQueue<>();

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
        modelRequests.clear();
        exposedToolNames.clear();
        parentResponses.clear();
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
            AgentRunView run = invocation.getArgument(0);
            // 子运行按受限白名单执行检索；父运行按脚本响应序列推进
            if (run != null && run.depth() > 0) {
                return new ModelTurnResult("文档检索完成",
                        List.of(new ModelToolCall("child-call-1", "list_project_documents",
                                json.createObjectNode())),
                        ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 100L);
            }
            ModelTurnResult next = parentResponses.poll();
            if (next != null) return next;
            return new ModelTurnResult("综合回答", List.of(), ModelFinishReason.STOP,
                    null, "OPENAI_COMPATIBLE", "model-a", 100L);
        });
        coordinator = coordinator();
        worker = new AgentWorker(repository, coordinator, events, json);
        ReflectionTestUtils.setField(worker, "cancellation", new AgentCancellationService(repository));
    }

    private AgentRuntimeCoordinator coordinator() {
        return coordinator(new AgentContextProperties(true, 20_000, 4_000, 2_000, java.util.Map.of()));
    }

    /** 指定上下文预算配置的协调器：回归用生产默认值（基建的预留 4000 会掩盖子运行输出预留边界）。 */
    private AgentRuntimeCoordinator coordinator(AgentContextProperties properties) {
        var json = new ObjectMapper();
        var access = mock(ProjectAccessGuard.class);
        Mockito.lenient().when(access.requireMember(any(), any())).thenReturn(ProjectRole.OWNER);
        var search = mock(DocumentSearchService.class);
        Mockito.lenient().when(search.search(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        var knowledgeTool = new KnowledgeSearchAgentTool(access, search, new KnowledgeContextBuilder(), json);
        var questionAlias = new ProjectQuestionAgentTool(knowledgeTool);
        var delegateTool = new DocumentResearchDelegateAgentTool(json);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(knowledgeTool, questionAlias, delegateTool));
        var executor = new AgentToolCallExecutor(repository, registry,
                new AgentCancellationService(repository), new AgentLoopGuard(), approvals, modelExecutor,
                new AgentToolResultSanitizer(json), json, events, new AgentToolScheduler());
        return new AgentRuntimeCoordinator(
                repository, assembler, new AgentSkillRegistry(), new AgentPlanService(repository, json),
                registry, new AgentCancellationService(repository), modelExecutor,
                new AgentConvergencePolicy(), json, events,
                properties,
                new AgentModelMessageComposer(repository, mock(AgentMemoryService.class), json), executor,
                new AgentContextSummarizer(repository, modelExecutor, json));
    }

    private com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext context(AgentRunView run) {
        // 子运行使用相同身份上下文（真实由组装器按 run 推导），role 为 OWNER 保证工具策略可见性差异可控
        return new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(), "OWNER", false,
                AgentPageContext.empty(),
                run.depth() > 0 ? AgentRuntimeLimits.defaults()
                        : AgentRuntimeLimits.forSkill(run.skillCode()),
                run.depth(), List.of());
    }

    private record Fixture(UUID user, UUID project, UUID session) {}

    private Fixture fixture() {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "del-" + user.toString().substring(0, 8), "test-only-hash", "Delegation");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "委派验收", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        var session = repository.createSession(project, user, "委派验收");
        return new Fixture(user, project, session.id());
    }

    private AgentRunView claimAndAdvance(Fixture fixture, UUID runId) {
        ClaimedAgentRun claimed = recovery.claim("worker-del", Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(runId);
        AgentRunView run = repository.findRun(fixture.project(), runId).orElseThrow();
        return run;
    }

    @Test
    void delegationCreatesChildRunAndParentWaitsThenCollectsResult() {
        Fixture fixture = fixture();
        // v1 共享额度语义：子步数断言（≤8）与切分断言都基于 12/8 的父额度
        AgentRunView run = v1RunAndClaim(fixture, "结合需求文档分析项目风险", "w1");

        // 父运行第一轮：发起委派
        parentResponses.add(new ModelTurnResult("需要委派研究",
                List.of(new ModelToolCall("parent-call-1", "delegate_document_research",
                        json.createObjectNode().put("objective",
                                "研究项目文档中的验收标准与依赖关系，给出引用来源"))),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 100L));
        AgentWorkerOutcome outcome = coordinator.advance(run);
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);

        // 子运行已创建：depth=1、受限角色、QUEUED、预算从父切出
        var children = repository.childRuns(fixture.project(), run.id());
        assertThat(children).hasSize(1);
        AgentRunView child = children.getFirst();
        assertThat(child.depth()).isEqualTo(1);
        assertThat(child.role()).isEqualTo("KNOWLEDGE_RESEARCHER");
        assertThat(child.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(child.goal()).contains("验收标准");
        assertThat(jdbc.queryForObject(
                "SELECT max_steps FROM agent_run WHERE id=?", Integer.class, child.id())).isLessThanOrEqualTo(8);
        // 委派受理步骤与幂等结果落库
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE run_id=? AND type='DELEGATION_REQUESTED'",
                Integer.class, run.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='parent-call-1'",
                String.class, run.id())).isEqualTo("SUCCEEDED");

        // 父运行在子运行完成前被再次领取：不发新模型请求，等待（QUEUED）。
        // claimNext 按 created_at 排序，父运行先被领取；跳过父运行直到子运行可领取
        int guard = 0;
        ClaimedAgentRun childClaimed;
        while (true) {
            childClaimed = recovery.claim("w-child", Duration.ofMinutes(6)).orElseThrow();
            if (childClaimed.id().equals(child.id())) break;
            // 父运行等待轮：不发新模型请求，重新排队
            assertThat(childClaimed.id()).isEqualTo(run.id());
            run = repository.findRun(fixture.project(), run.id()).orElseThrow();
            int before = modelRequests.size();
            AgentWorkerOutcome waiting = coordinator.advance(run);
            assertThat(waiting.status()).isEqualTo(AgentRunStatus.QUEUED);
            assertThat(modelRequests.size()).isEqualTo(before);
            if (++guard > 10) throw new AssertionError("子运行未被领取（等待循环超限）");
        }
        AgentRunView childView = repository.findRun(fixture.project(), child.id()).orElseThrow();
        AgentWorkerOutcome childOutcome = coordinator.advance(childView);
        assertThat(childOutcome.status()).isEqualTo(AgentRunStatus.QUEUED); // 工具执行后重新排队

        // 子运行第二轮：基于检索结果给出研究结论（文本收尾）→ 唤醒父运行
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            List<com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage> msgs = invocation.getArgument(1);
            AgentRunView r = invocation.getArgument(0);
            modelRequests.add(msgs == null ? "(no-messages)" : msgs.toString());
            if (r != null && r.depth() > 0) {
                return new ModelTurnResult("研究发现：文档 A 规定验收需要全部测试通过；文档 B 强调依赖顺序。",
                        List.of(), ModelFinishReason.STOP, null, "OPENAI_COMPATIBLE", "model-a", 100L);
            }
            ModelTurnResult next = parentResponses.poll();
            return next != null ? next : new ModelTurnResult("综合回答", List.of(),
                    ModelFinishReason.STOP, null, "OPENAI_COMPATIBLE", "model-a", 100L);
        });
        childClaimed = recovery.claim("w-child2", Duration.ofMinutes(6)).orElseThrow();
        assertThat(childClaimed.id()).isEqualTo(child.id());
        childView = repository.findRun(fixture.project(), child.id()).orElseThrow();
        childOutcome = coordinator.advance(childView);
        assertThat(childOutcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);

        // 子运行终态唤醒父运行：DELEGATION_COMPLETED step 落在父运行
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_step WHERE run_id=? AND type='DELEGATION_COMPLETED'",
                Integer.class, run.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_run WHERE id=?",
                String.class, child.id())).isEqualTo("SUCCEEDED");

        // 父运行被再次领取：读到子运行发现并综合最终回答（跳过可能的空领与子运行残留领取）
        int parentGuard = 0;
        ClaimedAgentRun parentClaimed;
        while (true) {
            parentClaimed = recovery.claim("w-parent", Duration.ofMinutes(6)).orElseThrow();
            if (parentClaimed.id().equals(run.id())) break;
            if (++parentGuard > 10) throw new AssertionError("父运行未被领取");
            if (parentClaimed.id().equals(child.id())) continue; // 不应发生：子已终态
        }
        run = repository.findRun(fixture.project(), run.id()).orElseThrow();
        parentResponses.clear();
        AgentWorkerOutcome parentOutcome = coordinator.advance(run);
        assertThat(parentOutcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        // 综合请求里包含子运行产出（UNTRUSTED 边界标记内）
        boolean injected = modelRequests.stream().anyMatch(m ->
                m.contains("CHILD_RESEARCH") && m.contains("文档 A 规定验收"));
        assertThat(injected).as("父综合请求应包含子运行研究发现；实际最后请求: "
                + modelRequests.get(modelRequests.size() - 1)).isTrue();
        // 父最终回答落为 ASSISTANT 消息（子运行产出不作为用户消息、不重复落第二份最终回答）
        var parentAnswer = jdbc.queryForList(
                "SELECT content FROM agent_message WHERE session_id=? AND role='ASSISTANT' ORDER BY created_at",
                String.class, fixture.session());
        assertThat(parentAnswer).isNotEmpty();
    }

    @Test
    void delegationIsIdempotentAcrossDuplicateInvocation() {
        Fixture fixture = fixture();
        AgentRunView run = v1RunAndClaim(fixture, "研究文档", "w-i1");
        UUID invocationId = UUID.randomUUID();
        var result = repository.documentResearchDelegationResult(run, invocationId.toString(), "研究目标 A");
        assertThat(result.path("status").asText()).isEqualTo("DELEGATED");
        UUID childId = UUID.fromString(result.path("childRunId").asText());

        // 重复受理（崩溃恢复重放）：返回同一子运行，不创建第二个
        var again = repository.documentResearchDelegationResult(run, invocationId.toString(), "研究目标 A");
        assertThat(again.path("childRunId").asText()).isEqualTo(childId.toString());
        assertThat(repository.childRuns(fixture.project(), run.id())).hasSize(1);
        // children_used 只增加一次
        assertThat(jdbc.queryForObject("SELECT children_used FROM agent_run WHERE id=?",
                Integer.class, run.id())).isEqualTo(1);
    }

    @Test
    void delegationRejectsPauseIntentAndChildDepth() {
        Fixture fixture = fixture();
        AgentRunView run = v1RunAndClaim(fixture, "研究文档", "w-p0");
        // 暂停意图先落库：拒绝受理
        jdbc.update("UPDATE agent_run SET pause_requested_at=now() WHERE id=?", run.id());
        var pausedRun = repository.findRun(fixture.project(), run.id()).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                repository.documentResearchDelegationResult(pausedRun, UUID.randomUUID().toString(), "目标"))
                .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class)
                .hasMessageContaining("暂停");
        jdbc.update("UPDATE agent_run SET pause_requested_at=NULL WHERE id=?", run.id());

        // depth=1 的子运行不能再次委派（子运行不受租约约束，直接以视图调用）
        var result = repository.documentResearchDelegationResult(
                repository.findRun(fixture.project(), run.id()).orElseThrow(),
                UUID.randomUUID().toString(), "研究目标 B");
        UUID childId = UUID.fromString(result.path("childRunId").asText());
        var child = repository.findRun(fixture.project(), childId).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                repository.documentResearchDelegationResult(child, UUID.randomUUID().toString(), "嵌套委派"))
                .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);
    }

    @Test
    void childRunHasRestrictedToolWhitelist() {
        // 子运行的 skills 选择经 restrictedChildSkill 收窄：允许集合即受限白名单
        var registry = new AgentToolRegistry(List.of(new DocumentResearchDelegateAgentTool(json)));
        var childContext = new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OWNER", false,
                AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 1, List.of());
        var base = new com.shitulelv.aicollab.agent.domain.model.builtin.ProjectResearchSkill();
        // 白名单中的工具（如 list_project_documents）不在 registry 时 definitionsFor 只给已注册项；
        // 关键断言：委派工具本身对 depth=1 不可见/不可执行
        assertThat(registry.definitionsFor(childContext, base))
                .noneMatch(d -> d.name().equals("delegate_document_research"));
    }

    // ===== 2026-10-07 审查探针转正：委派隔离/恢复/白名单/批次/用量/引用/上下文边界 =====

    /**
     * 本文件的父运行夹具默认钉住 <b>v1 共享额度</b>语义。
     *
     * <p>`createRun` 现在默认创建 v2 运行（累计 token 只统计 + 父子独立执行额度）。
     * 但本文件的用例检验的是 v1 的切分与配额事实：子额度从父剩余切出、扣委派自身一次工具、
     * 父综合预留 2 步、工具额度按 12/8 封顶、容不下则拒绝受理、COMBINED 旧语义等。
     * 这些语义在 v1 下必须保持（旧运行恢复/暂停续跑不重解释），因此夹具显式钉住版本与额度，
     * 让用例测的是它原本要测的那件事，而不是随默认值漂移成 v2 的独立额度。</p>
     *
     * <p>v2 的独立额度与新语义由 {@code AgentResourcePolicyTest}、
     * {@code AgentDelegationAdmissionTest} 与 `newV2ParentWithClaim` 的用例覆盖。</p>
     */
    private AgentRunView newParentWithClaim(String worker) {
        Fixture fixture = fixture();
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                "Research the project documents and report reliable findings", false, null, null);
        jdbc.update("""
                UPDATE agent_run SET context_policy_version=1, max_steps=12, max_tool_calls=8,
                  max_input_tokens=50000, max_output_tokens=20000 WHERE id=?
                """, run.id());
        repository.claimNext(worker, java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    /** v2 新策略父运行：累计 token 无上限（NULL），父子独立执行额度。 */
    private AgentRunView newV2ParentWithClaim(String worker) {
        Fixture fixture = fixture();
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                "Research the project documents and report reliable findings", false, null, null);
        repository.claimNext(worker, java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    /**
     * 创建并领取一个 v1 兼容父运行（供直接调 {@code createRun} 的用例复用）：
     * 钉住旧策略与 12/8 额度，使 v1 切分断言（子 ≤8 步、子工具 = 父剩余 − 委派自身）继续成立。
     */
    private AgentRunView v1RunAndClaim(Fixture fixture, String goal, String worker) {
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                goal, false, null, null);
        jdbc.update("""
                UPDATE agent_run SET context_policy_version=1, max_steps=12, max_tool_calls=8,
                  max_input_tokens=50000, max_output_tokens=20000 WHERE id=?
                """, run.id());
        recovery.claim(worker, Duration.ofMinutes(6)).orElseThrow();
        return repository.findRun(fixture.project(), run.id()).orElseThrow();
    }

    private AgentRunView delegateChild(AgentRunView parent) {
        var result = repository.documentResearchDelegationResult(parent, UUID.randomUUID().toString(),
                "Research the acceptance criteria in project documents with sources");
        return repository.findRun(parent.projectId(), UUID.fromString(result.path("childRunId").asText())).orElseThrow();
    }

    private ModelTurnResult parentTurn(List<ModelToolCall> calls) {
        return new ModelTurnResult("Research request", calls,
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "model-a", 100L);
    }

    /** R1：子运行最终回答不得成为主会话 ASSISTANT 消息。 */
    @Test
    void childAnswerMustNotBecomeMainSessionMessage() {
        delegationCreatesChildRunAndParentWaitsThenCollectsResult();
        int leaked = jdbc.queryForObject("""
                SELECT count(*) FROM agent_message m JOIN agent_run r ON r.id=m.run_id
                WHERE m.role='ASSISTANT' AND r.depth=1
                """, Integer.class);
        assertThat(leaked).as("child ASSISTANT messages in the shared session").isZero();
    }

    /** R2：会话最新运行必须是主运行，委派子运行不能被恢复/订阅/控制入口当成主运行。 */
    @Test
    void latestSessionRunMustRemainTheRoot() {
        AgentRunView parent = newParentWithClaim("w-latest");
        AgentRunView child = delegateChild(parent);
        assertThat(repository.findLatestRun(parent.projectId(), parent.sessionId()).orElseThrow().id())
                .as("session latest run must be parent, not child %s", child.id()).isEqualTo(parent.id());
        // 会话摘要的最新运行同样只看主运行
        var summaries = repository.listSessionSummaries(parent.projectId(), 10);
        assertThat(summaries).anyMatch(s -> parent.id().equals(s.latestRunId()));
    }

    /** R3：受限子场景不得接收基础只读集合扩出的任务/记忆/统计工具（暴露与执行复核一致）。 */
    @Test
    void restrictedChildMustNotReceiveCommonRootTools() {
        var registry = coordinatorRegistry();
        var childContext = new com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OWNER", false,
                AgentPageContext.empty(), AgentRuntimeLimits.defaults(), 1, List.of());
        // 与生产一致的受限子场景：经由协调器的收窄逻辑构造（公开静态判定可测，不用反射）
        var research = new com.shitulelv.aicollab.agent.domain.model.builtin.ProjectResearchSkill();
        var restricted = new AgentSkill() {
            @Override public String code() { return "CHILD_DOCUMENT_RESEARCH"; }
            @Override public String displayName() { return "文档研究子任务"; }
            @Override public String description() { return research.description(); }
            @Override public java.util.Set<String> recommendedRoutes() { return research.recommendedRoutes(); }
            @Override public java.util.Set<String> allowedTools() {
                return DocumentResearchDelegateAgentTool.CHILD_ALLOWED_TOOLS;
            }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return research.inputSchema(); }
            @Override public boolean allowWriteTools() { return false; }
            @Override public boolean allowExternalTools() { return false; }
            @Override public String instruction() { return research.instruction(); }
            @Override public String outputContract() { return research.outputContract(); }
        };
        assertThat(AgentRuntimeCoordinator.isChildResearchSkill(restricted)).isTrue();
        var definitions = registry.definitionsFor(childContext, restricted);
        // 白名单内的文档工具可见（已注册的）
        assertThat(definitions).anyMatch(d -> d.name().equals("search_project_knowledge"));
        // 基础集合的其余工具不得经基础集合扩大给子场景
        assertThat(definitions)
                .noneMatch(d -> AgentToolRegistry.baseReadOnlyTools().contains(d.name())
                        && !DocumentResearchDelegateAgentTool.CHILD_ALLOWED_TOOLS.contains(d.name()));
        assertThat(AgentToolRegistry.baseReadOnlyTools()).contains("get_task");
    }

    private AgentToolRegistry coordinatorRegistry() {
        var access = org.mockito.Mockito.mock(com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard.class);
        org.mockito.Mockito.lenient().when(access.requireMember(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.shitulelv.aicollab.project.domain.model.ProjectRole.OWNER);
        var search = org.mockito.Mockito.mock(DocumentSearchService.class);
        org.mockito.Mockito.lenient().when(search.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        var knowledgeTool = new KnowledgeSearchAgentTool(access, search, new KnowledgeContextBuilder(), json);
        var taskTool = new com.shitulelv.aicollab.agent.infrastructure.tool.TaskGetAgentTool(
                org.mockito.Mockito.mock(com.shitulelv.aicollab.work.application.service.TaskApplicationService.class), json);
        var delegateTool = new DocumentResearchDelegateAgentTool(json);
        return new AgentToolRegistry(List.of(knowledgeTool, taskTool, delegateTool));
    }

    /** R5：父运行处于 PAUSED 时子用量仍被回收，且父不被自动解除暂停。 */
    @Test
    void pausedParentMustStillCollectChildUsage() {
        AgentRunView parent = newParentWithClaim("w-paused");
        AgentRunView child = delegateChild(parent);
        repository.requestPause(parent.projectId(), parent.id());
        var claimed = repository.claimNext("w-paused-child", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(child.id());
        jdbc.update("""
                UPDATE agent_run SET input_tokens_used=1234,input_tokens_actual=1234,
                  output_tokens_used=123,output_tokens_actual=123,steps_used=2,tool_calls_used=1 WHERE id=?
                """, child.id());
        child = repository.findRun(parent.projectId(), child.id()).orElseThrow();
        repository.recordFinal(child, "Child findings with coverage gaps", List.of());
        AgentRunView after = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        assertThat(after.status().name()).as("pause must not be lifted by child collection").isEqualTo("PAUSED");
        assertThat(after.inputTokensActual()).as("usage must not disappear while parent is paused").isEqualTo(1234);
        assertThat(after.outputTokensActual()).isEqualTo(123);
        // 父运行自身用量（委派轮）+ 子运行回收量（2 步/1 调用）；
        // tool_calls_used 含委派受理自身消耗（R8 修复）+ 子运行回收。
        // steps_used：委派轮 1 + 委派受理不占推进步（SEPARATED 语义）+ 子回收 2 = 3
        assertThat(after.stepsUsed()).isEqualTo(3);
        assertThat(after.toolCallsUsed()).isEqualTo(2);
    }

    /** R4：混合批次中被拒绝的委派不得落入普通执行产生伪成功回执，也不得创建子运行。 */
    @Test
    void rejectedMixedBatchMustNotPublishFakeDelegationReceipt() {
        AgentRunView parent = newParentWithClaim("w-mixed");
        parentResponses.add(parentTurn(List.of(
                new ModelToolCall("mixed-delegate", "delegate_document_research",
                        json.createObjectNode().put("objective", "Research project documents with reliable sources")),
                new ModelToolCall("mixed-search", "search_project_knowledge",
                        json.createObjectNode().put("query", "acceptance criteria")))));
        coordinator.advance(parent);
        assertThat(repository.childRuns(parent.projectId(), parent.id())).isEmpty();
        String status = jdbc.queryForObject("""
                SELECT status FROM agent_tool_invocation WHERE run_id=? AND tool_call_id='mixed-delegate'
                """, String.class, parent.id());
        assertThat(status).as("rejected delegate must not become a successful DELEGATED receipt").isNotEqualTo("SUCCEEDED");
    }

    /** R9：子运行的模型请求不得包含父对话历史（只有委派目标与自身观察）。 */
    @Test
    void childModelMustNotReceiveParentConversationHistory() {
        AgentRunView parent = newParentWithClaim("w-ctx");
        String marker = "PRIVATE_PARENT_HISTORY_NOT_PART_OF_DELEGATED_OBJECTIVE";
        jdbc.update("INSERT INTO agent_message(session_id,run_id,role,content) VALUES (?,?,'USER',?)",
                parent.sessionId(), parent.id(), marker);
        AgentRunView child = delegateChild(parent);
        var claimed = repository.claimNext("w-ctx-child", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(child.id());
        coordinator.advance(repository.findRun(parent.projectId(), child.id()).orElseThrow());
        assertThat(modelRequests).noneMatch(request -> request.contains(marker));
    }

    /** R9 摘要面：子运行不得对共享会话触发摘要（摘要对象是父对话历史，不属于子任务）。 */
    @Test
    void childRunMustNotTriggerConversationSummary() {
        AgentRunView parent = newParentWithClaim("w-sum");
        // 父对话留一条足够长的历史消息（子运行可见的摘要候选源）
        jdbc.update("INSERT INTO agent_message(session_id,run_id,role,content) VALUES (?,?,'USER',?)",
                parent.sessionId(), parent.id(), "父对话历史内容，不属于委派任务。".repeat(30));
        AgentRunView child = delegateChild(parent);
        var claimed = repository.claimNext("w-sum-child", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(child.id());
        var before = jdbc.queryForList(
                "SELECT working_state->'summary'->>'text' FROM agent_session WHERE id=?",
                String.class, parent.sessionId());
        coordinator.advance(repository.findRun(parent.projectId(), child.id()).orElseThrow());
        var after = jdbc.queryForList(
                "SELECT working_state->'summary'->>'text' FROM agent_session WHERE id=?",
                String.class, parent.sessionId());
        assertThat(after.getFirst()).as("child run must not write a session summary").isEqualTo(before.getFirst());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM agent_step WHERE run_id=? AND reason='CONTEXT_SUMMARY'
                """, Integer.class, child.id())).isZero();
    }

    /**
     * R12 运行级输出预留：委派子运行的输出硬上限（8000）与生产全局预留（outputReserveTokens=8000）
     * 恰好相等，固定预留会把子运行第一轮之后的任何剩余额度（8000-31=7969）判成
     * "预留已耗尽"——needsFinalRequest 立即强制收尾、摘要预留检查直接超限，
     * 子运行永远无法发起第二次模型请求（真实环境三次委派实验复现）。
     * 测试基建的预留值（4000）会掩盖该边界，本用例用生产默认配置锁定修复行为：
     * 预留按运行自身预算等比收紧，子运行消耗输出后必须能继续第二轮请求并正常收尾。
     */
    @Test
    void childRunWithProductionOutputReserveMustIssueSecondModelRequest() {
        AgentRuntimeCoordinator productionCoordinator = coordinator(AgentContextProperties.defaults());
        AgentRunView parent = newParentWithClaim("w-res");
        AgentRunView child = delegateChild(parent);

        // 子运行第一轮：留下真实输出消耗（usage 与线上失败一致：input 3512 / output 31）
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            AgentRunView r = invocation.getArgument(0);
            modelRequests.add(invocation.getArgument(1) == null ? "(no-messages)"
                    : invocation.getArgument(1).toString());
            if (r != null && r.depth() > 0) {
                return new ModelTurnResult("开始检索项目文档",
                        List.of(new ModelToolCall("child-call-r1", "list_project_documents",
                                json.createObjectNode())),
                        ModelFinishReason.TOOL_CALLS, new ModelUsage(3512, 31),
                        "OPENAI_COMPATIBLE", "model-a", 100L);
            }
            ModelTurnResult next = parentResponses.poll();
            return next != null ? next : new ModelTurnResult("综合回答", List.of(),
                    ModelFinishReason.STOP, null, "OPENAI_COMPATIBLE", "model-a", 100L);
        });
        var childClaimed = repository.claimNext("w-res-child1", java.time.OffsetDateTime.now(),
                Duration.ofMinutes(6)).orElseThrow();
        assertThat(childClaimed.id()).isEqualTo(child.id());
        productionCoordinator.advance(repository.findRun(parent.projectId(), child.id()).orElseThrow());
        assertThat(jdbc.queryForObject(
                "SELECT output_tokens_used FROM agent_run WHERE id=?", Integer.class, child.id()))
                .as("第一轮必须留下真实输出消耗，否则用例没有触发预留边界")
                .isGreaterThan(0);

        // 第二轮：修复前在生产预留值下直接 BUDGET_EXCEEDED；修复后继续请求并文本收尾
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            AgentRunView r = invocation.getArgument(0);
            modelRequests.add(invocation.getArgument(1) == null ? "(no-messages)"
                    : invocation.getArgument(1).toString());
            if (r != null && r.depth() > 0) {
                return new ModelTurnResult("研究发现：验收标准要求评分去最高最低取平均，批量导入单次上限 500 人。",
                        List.of(), ModelFinishReason.STOP, new ModelUsage(4200, 500),
                        "OPENAI_COMPATIBLE", "model-a", 100L);
            }
            ModelTurnResult next = parentResponses.poll();
            return next != null ? next : new ModelTurnResult("综合回答", List.of(),
                    ModelFinishReason.STOP, null, "OPENAI_COMPATIBLE", "model-a", 100L);
        });
        var claimed = repository.claimNext("w-res-child2", java.time.OffsetDateTime.now(),
                Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(child.id());
        var outcome = productionCoordinator.advance(
                repository.findRun(parent.projectId(), child.id()).orElseThrow());
        assertThat(outcome.status())
                .as("子运行消耗输出后必须能发起第二次模型请求并正常收尾")
                .isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_run WHERE id=?",
                String.class, child.id())).isEqualTo("SUCCEEDED");
    }

    /**
     * R12b 子运行预算部分回答随回收进入父综合：证据兜底产出的部分回答此前只停留在
     * 子运行运行结果里，DELEGATION_COMPLETED 的 content 仅回传错误码，父运行综合时
     * 看不到子运行已取得的工具证据（真实委派实验第三次运行复现："子 Agent 产出：零"）。
     * 修复后错误码占位必须被部分回答覆盖。
     */
    @Test
    void childBudgetPartialAnswerMustReachParentCollection() {
        AgentRunView parent = newParentWithClaim("w-pb");
        AgentRunView child = delegateChild(parent);
        var claimed = repository.claimNext("w-pb-child", java.time.OffsetDateTime.now(),
                Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(child.id());
        // 子运行先留下一次成功的工具结果（证据兜底的数据来源）
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,tool_name,output_json,reason)
                VALUES (?,1,'TOOL_CALL_COMPLETED','list_project_documents',?::jsonb,'TOOL_SUCCESS')
                """, child.id(), "{\"data\":{\"items\":[{\"filename\":\"验收标准v2.1.md\"}]}}");
        child = repository.findRun(parent.projectId(), child.id()).orElseThrow();

        String partial = "模型未能在本次运行预算内生成完整总结，先返回已取得的结果：\n"
                + "- list_project_documents: {\"data\":{\"items\":[{\"filename\":\"验收标准v2.1.md\"}]}}";
        repository.recordBudgetPartialAnswer(child, partial, true);

        var completed = jdbc.queryForObject("""
                SELECT output_json->>'status', output_json->>'content' FROM agent_step
                WHERE run_id=? AND type='DELEGATION_COMPLETED'
                """, (rs, n) -> rs.getString(1) + "|" + rs.getString(2), parent.id());
        assertThat(completed).as("回收状态如实标注预算超限").startsWith("BUDGET_EXCEEDED");
        assertThat(completed).as("回收内容必须是子运行的部分回答而不是错误码占位")
                .contains("list_project_documents");
        // 父运行已回收子运行用量并转入可领取状态
        var after = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        assertThat(after.inputTokensActual()).isGreaterThanOrEqualTo(0);
    }
    /** R8：受理成功的委派必须计入父运行的工具配额（不出现免费调用）。 */
    @Test
    void acceptedDelegationMustConsumeAToolCall() {
        AgentRunView parent = newParentWithClaim("w-quota");
        parentResponses.add(parentTurn(List.of(
                new ModelToolCall("single-delegate", "delegate_document_research",
                        json.createObjectNode().put("objective", "Research project documents with reliable sources")))));
        coordinator.advance(parent);
        assertThat(repository.childRuns(parent.projectId(), parent.id())).hasSize(1);
        var after = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        // 委派受理本身 + 受理路径落库的工具步骤：委派是一次真实的工具消耗（R8 修复），
        // 具体计数 = 模型轮步骤 + 委派受理步骤，均不超过配额语义
        assertThat(after.toolCallsUsed())
                .as("successful delegation consumes tool quota").isEqualTo(1);
        assertThat(after.childrenUsed()).isEqualTo(1);
    }

    /** R7：子研究的有效来源身份经 DELEGATION_COMPLETED 进入父最终回答的引用集合。 */
    @Test
    void parentFinalAnswerMustRetainChildSourceIdentities() {
        AgentRunView parent = newParentWithClaim("w-cite");
        AgentRunView child = delegateChild(parent);
        UUID document = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,uploaded_by) VALUES (?,?,'Review source','review.txt','text/plain',100,?,?)
                """, document, parent.projectId(), "review/" + document, parent.requesterId());
        jdbc.update("""
                INSERT INTO document_body(document_id,snapshot_id,original_content_hash,parse_version)
                VALUES (?,?,'review-hash','review-v1')
                """, document, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO document_body_chunk(id,document_id,chunk_no,content,content_hash)
                VALUES (?,?,0,'Acceptance requires test success','review-hash')
                """, chunk, document);
        var citation = new com.shitulelv.aicollab.agent.domain.model.AgentCitation(document, chunk,
                "review.txt", "Acceptance", null, "Acceptance requires test success", 1.0);
        var result = json.createObjectNode().put("status", "SUCCEEDED");
        result.set("citations", json.valueToTree(List.of(citation)));
        // 子运行先领取进入 RUNNING（recordToolResult 的租约边界要求）
        var childClaim = repository.claimNext("w-cite-child", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(childClaim.id()).isEqualTo(child.id());
        child = repository.findRun(parent.projectId(), child.id()).orElseThrow();
        child = repository.recordToolResult(child, "read_document_section", json.createObjectNode(), result, false);
        repository.recordFinal(child, "The child found acceptance criteria", List.of());
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(output_json->'citations') FROM agent_step
                WHERE run_id=? AND type='FINAL_ANSWER'
                """, Integer.class, child.id())).isEqualTo(1);
        // DELEGATION_COMPLETED 已携带子来源身份
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(output_json->'citations') FROM agent_step
                WHERE run_id=? AND type='DELEGATION_COMPLETED'
                """, Integer.class, parent.id())).isEqualTo(1);
        // 父运行综合后引用集合保留子来源（recordFinal 的 citations 参数为协调器移交值）
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        var claimed = repository.claimNext("w-cite-parent", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        parentResponses.clear();
        var parentOutcome = coordinator.advance(parent);
        assertThat(parentOutcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(citations_json) FROM agent_message
                WHERE run_id=? AND role='ASSISTANT'
                """, Integer.class, parent.id())).as("source identities must survive parent synthesis").isEqualTo(1);
    }

    /** 重复回收幂等：子运行重复收口不产生第二条 DELEGATION_COMPLETED、不重复累计用量。 */
    @Test
    void childCollectionIsIdempotentAcrossRepeatedFinalization() {
        AgentRunView parent = newParentWithClaim("w-idem");
        AgentRunView child = delegateChild(parent);
        jdbc.update("UPDATE agent_run SET input_tokens_used=500,input_tokens_actual=500 WHERE id=?", child.id());
        // 恢复/重放场景：子运行先领取进入 RUNNING，第一次收口用最新视图；
        // 第二次用旧视图模拟旧 worker 迟到提交（终态 CAS 不再匹配）
        var childClaim = repository.claimNext("w-idem-child", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(childClaim.id()).isEqualTo(child.id());
        child = repository.findRun(parent.projectId(), child.id()).orElseThrow();
        repository.recordFinal(child, "findings", List.of());
        var staleChild = child;
        child = repository.findRun(parent.projectId(), child.id()).orElseThrow();
        // 第二次终态 UPDATE 不再匹配（运行已 SUCCEEDED），回收路径因幂等键直接返回
        org.assertj.core.api.Assertions.assertThatCode(() -> repository.recordFinal(staleChild, "findings", List.of()))
                .as("stale replay must not corrupt state").isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM agent_step WHERE run_id=? AND type='DELEGATION_COMPLETED'
                """, Integer.class, parent.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT input_tokens_actual FROM agent_run WHERE id=?",
                Integer.class, parent.id())).as("usage collected exactly once").isEqualTo(500);
    }

    /** 子运行失败/取消同样唤醒父运行且回收已发生消耗，父如实收到失败状态。 */
    @Test
    void failedChildStillWakesParentWithUsage() {
        AgentRunView parent = newParentWithClaim("w-fail");
        AgentRunView child = delegateChild(parent);
        // 子运行先被领取进入 RUNNING（recordCanceled 要求可取消状态）
        var claimed = repository.claimNext("w-fail-child", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(child.id());
        jdbc.update("UPDATE agent_run SET input_tokens_actual=800,output_tokens_actual=60 WHERE id=?", child.id());
        child = repository.findRun(parent.projectId(), child.id()).orElseThrow();
        repository.recordCanceled(child);
        var completed = jdbc.queryForObject("""
                SELECT output_json->>'status', output_json->>'content' FROM agent_step
                WHERE run_id=? AND type='DELEGATION_COMPLETED'
                """, (rs, n) -> rs.getString(1) + "|" + rs.getString(2), parent.id());
        assertThat(completed).startsWith("CANCELED");
        var after = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        assertThat(after.inputTokensActual()).isEqualTo(800);
        assertThat(after.outputTokensActual()).isEqualTo(60);
    }

    // ===== 2026-10-07 第三轮：预算语义分离（模型推进 vs 工具调用）与收尾契约 =====

    /**
     * 并行工具批次不得耗尽推进预算：旧语义下一轮 4 个并行调用记 4 步，子运行
     * （提纲+检索+正文读取）在 8 步预算终点提前耗尽（真实 B-narrow 实验复现）。
     * 新语义（SEPARATED）下一次模型轮计一次推进、最终回答落库保留一次收口，
     * 工具结果只计 tool_calls_used（4+2 并行调用仍计 6 次工具额度）。
     */
    @Test
    void childParallelToolBatchesMustNotExhaustProgressionBudget() {
        AgentRunView parent = newParentWithClaim("w-para");
        AgentRunView child = delegateChild(parent);
        java.util.concurrent.atomic.AtomicInteger childTurns = new java.util.concurrent.atomic.AtomicInteger();
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            AgentRunView r = invocation.getArgument(0);
            modelRequests.add(invocation.getArgument(1) == null ? "(no-messages)"
                    : invocation.getArgument(1).toString());
            if (r != null && r.depth() > 0) {
                int turn = childTurns.incrementAndGet();
                if (turn == 1) {
                    return new ModelTurnResult("并行检索四组资料",
                            java.util.stream.IntStream.range(0, 4).mapToObj(i -> new ModelToolCall(
                                            "child-p" + i, "search_project_knowledge",
                                            json.createObjectNode().put("query", "验收标准 " + i)))
                                    .toList(),
                            ModelFinishReason.TOOL_CALLS, new ModelUsage(3000, 40),
                            "OPENAI_COMPATIBLE", "model-a", 100L);
                }
                if (turn == 2) {
                    return new ModelTurnResult("补充检索两组",
                            java.util.stream.IntStream.range(4, 6).mapToObj(i -> new ModelToolCall(
                                            "child-p" + i, "search_project_knowledge",
                                            json.createObjectNode().put("query", "验收标准 " + i)))
                                    .toList(),
                            ModelFinishReason.TOOL_CALLS, new ModelUsage(3200, 40),
                            "OPENAI_COMPATIBLE", "model-a", 100L);
                }
                return new ModelTurnResult("研究发现：评分聚合要求去最高最低取平均，批量导入单次上限 500 人。",
                        List.of(), ModelFinishReason.STOP, new ModelUsage(3400, 400),
                        "OPENAI_COMPATIBLE", "model-a", 100L);
            }
            ModelTurnResult next = parentResponses.poll();
            return next != null ? next : new ModelTurnResult("综合回答", List.of(),
                    ModelFinishReason.STOP, null, "OPENAI_COMPATIBLE", "model-a", 100L);
        });
        AgentWorkerOutcome outcome = null;
        int guard = 0;
        while (outcome == null || outcome.status() == AgentRunStatus.QUEUED) {
            var loopClaim = repository.claimNext("w-para-loop", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
            if (loopClaim.id().equals(parent.id())) {
                // 父运行等待子运行：等待轮不发模型请求、重新排队
                assertThat(coordinator.advance(repository.findRun(parent.projectId(), parent.id()).orElseThrow()).status())
                        .isEqualTo(AgentRunStatus.QUEUED);
            } else {
                assertThat(loopClaim.id()).isEqualTo(child.id());
                outcome = coordinator.advance(repository.findRun(parent.projectId(), child.id()).orElseThrow());
            }
            if (++guard > 16) throw new AssertionError("委派推进循环超限");
        }
        assertThat(outcome.status())
                .as("并行工具批次不应耗尽子运行推进预算")
                .isEqualTo(AgentRunStatus.SUCCEEDED);
        var after = repository.findRun(parent.projectId(), child.id()).orElseThrow();
        assertThat(after.toolCallsUsed()).as("6 个工具调用仍占工具额度").isEqualTo(6);
        assertThat(after.stepsUsed()).as("推进步 = 3 个模型轮 + 最终回答落库").isEqualTo(4);
    }

    /**
     * 工具额度用完但已有可信证据且模型预算尚足：超额度批次应进入无工具总结而不是直接硬停。
     * 整批无法受理时按请求规模消耗工具额度（封顶）、跳过整批并重新排队，
     * 下一次准入由收敛策略判定为 FINALIZE（收尾轮禁工具）。
     */
    @Test
    void overQuotaToolBatchMustEnterNoToolSummaryWhenEvidenceExists() {
        AgentRunView parent = newParentWithClaim("w-ovq");
        // 已有可信证据（一次成功工具结果）；工具额度只剩 2，而模型请求整批 4 个调用
        jdbc.update("UPDATE agent_run SET tool_calls_used=6 WHERE id=?", parent.id());
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,tool_name,output_json,reason)
                VALUES (?,1,'TOOL_CALL_COMPLETED','search_project_knowledge',?::jsonb,'TOOL_SUCCESS')
                """, parent.id(), "{\"data\":{\"items\":[{\"filename\":\"验收标准v2.1.md\"}]}}");
        parentResponses.add(parentTurn(java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> new ModelToolCall("ovq-" + i, "search_project_knowledge",
                        json.createObjectNode().put("query", "评分模块 " + i)))
                .toList()));
        AgentWorkerOutcome first = coordinator.advance(parent);
        assertThat(first.status())
                .as("超额度批次应重新排队进入无工具总结，而不是硬停为 BUDGET_EXCEEDED")
                .isEqualTo(AgentRunStatus.QUEUED);
        var claimed = repository.claimNext("w-ovq-2", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        parentResponses.clear();
        AgentWorkerOutcome second = coordinator.advance(
                repository.findRun(parent.projectId(), parent.id()).orElseThrow());
        assertThat(second.status()).as("无工具总结轮应正常收口").isEqualTo(AgentRunStatus.SUCCEEDED);
        var after = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        assertThat(after.toolCallsUsed()).as("被拒批次按请求规模消耗工具额度（封顶）").isEqualTo(8);
    }

    /** 委派自身占一次工具调用（不占推进步）；子额度扣除该调用并预留父综合收尾。 */
    @Test
    void delegationSplitChargesDelegationToolAndReservesParentSynthesis() {
        AgentRunView parent = newParentWithClaim("w-split");
        AgentRunView child = delegateChild(parent);
        var after = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        assertThat(after.toolCallsUsed()).as("委派受理消耗一次工具调用").isEqualTo(1);
        assertThat(after.stepsUsed()).as("委派受理不再占推进步（SEPARATED）").isZero();
        var row = jdbc.queryForMap(
                "SELECT max_steps,max_tool_calls,max_input_tokens,max_output_tokens,budget_semantics FROM agent_run WHERE id=?",
                child.id());
        assertThat(row.get("max_tool_calls")).as("子工具额度 = 父剩余扣除委派自身一次调用").isEqualTo(7);
        assertThat(row.get("max_steps")).as("子步数 = 父剩余扣除父综合收尾预留 2").isEqualTo(8);
        assertThat(row.get("max_input_tokens")).as("子输入 = 父剩余的一半（等比预留，父综合留一半）").isEqualTo(25_000);
        assertThat(row.get("max_output_tokens")).as("子输出 = 父剩余的一半").isEqualTo(8_000);
        assertThat(row.get("budget_semantics")).as("子运行继承父运行预算语义").isEqualTo("SEPARATED");
    }

    /** 剩余额度连子运行最小研究（1 轮）与收尾（收尾轮+落库）都容纳不了：明确拒绝受理，不先启动再注定失败。 */
    @Test
    void delegationRejectedWhenRemainingBudgetCannotFitChildResearchAndClosing() {
        AgentRunView parent = newParentWithClaim("w-rej");
        // 父剩余推进步 3：扣除父综合收尾预留 2 后只剩 1，容不下子运行最小研究与收尾
        jdbc.update("UPDATE agent_run SET steps_used=max_steps-3 WHERE id=?", parent.id());
        AgentRunView exhausted = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                repository.documentResearchDelegationResult(exhausted, UUID.randomUUID().toString(), "研究目标"))
                .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class)
                .hasMessageContaining("拒绝受理");
        assertThat(repository.childRuns(parent.projectId(), parent.id())).isEmpty();
    }

    /** 旧预算运行（COMBINED）恢复推进保持旧记账：工具结果仍占推进步；子运行继承其语义。 */
    @Test
    void legacyCombinedSemanticsKeepsOldStepAccounting() {
        AgentRunView parent = newParentWithClaim("w-leg");
        jdbc.update("UPDATE agent_run SET budget_semantics='COMBINED' WHERE id=?", parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        parent = repository.recordModelTurn(parent, new ModelTurnResult("检查资料", List.of(),
                ModelFinishReason.STOP, new ModelUsage(100, 50), "OPENAI_COMPATIBLE", "model-a", 100L));
        parent = repository.recordToolResult(parent, "search_project_knowledge",
                json.createObjectNode(), json.createObjectNode().put("status", "SUCCEEDED"), false);
        assertThat(parent.stepsUsed()).as("COMBINED 旧语义下工具结果仍占推进步").isEqualTo(2);
        assertThat(parent.toolCallsUsed()).isEqualTo(1);
        var result = repository.documentResearchDelegationResult(parent, UUID.randomUUID().toString(), "研究目标");
        var child = repository.findRun(parent.projectId(), UUID.fromString(result.path("childRunId").asText())).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT budget_semantics FROM agent_run WHERE id=?",
                String.class, child.id())).as("子运行继承父运行预算语义").isEqualTo("COMBINED");
    }

    /**
     * R12c 委派型父运行的预算部分回答必须包含已回收的子运行研究产出：
     * 委派型运行的工具消耗记在子运行名下（回收并入父预算），父运行自己没有
     * TOOL_CALL_COMPLETED 步骤——此前证据兜底只看自有工具证据，回收后批量补读
     * 被预算整批拒绝时兜底为空，连子运行已取得的研究产出都交付不了
     * （真实委派实验第三次运行复现：运行终止且无任何最终回答落库）。
     */
    @Test
    void delegationParentBudgetFallbackMustCarryChildFindings() {
        AgentRunView parent = newParentWithClaim("w-cf");
        // 已回收的子运行产出（DELEGATION_COMPLETED，BUDGET_EXCEEDED + 部分回答）
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,1,'DELEGATION_COMPLETED',?::jsonb,'Specialist child run completed')
                """, parent.id(), """
                {"childRunId":"11111111-1111-1111-1111-111111111111","status":"BUDGET_EXCEEDED",
                 "content":"模型未能在本次运行预算内生成完整总结，先返回已取得的结果：\\n- get_document_outline: 评分聚合要求去最高最低取平均，批量导入单次上限 500 人。","citations":[]}
                """);
        // 父运行步骤预算逼近终点（剩余 1 步 < 收尾所需 2 步 → EXHAUSTED）
        jdbc.update("UPDATE agent_run SET steps_used=max_steps-1 WHERE id=?", parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();

        AgentWorkerOutcome outcome = coordinator.advance(parent);
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.answer())
                .as("预算部分回答必须携带已回收子运行的研究产出")
                .contains("get_document_outline").contains("评分聚合");
        // depth=0 的部分回答落为会话 ASSISTANT 消息（用户可见）
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM agent_message
                WHERE session_id=? AND role='ASSISTANT' AND content LIKE '%get_document_outline%'
                """, Integer.class, parent.sessionId())).isEqualTo(1);
    }

    // ===== 2026-10-07 第四轮：委派结果的资料覆盖传递 =====

    private AgentRunView claimChild(AgentRunView parent, AgentRunView child, String worker) {
        var claimed = repository.claimNext(worker, java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(child.id());
        return repository.findRun(parent.projectId(), child.id()).orElseThrow();
    }

    private ObjectNode toolOutput(ObjectNode data) {
        return json.createObjectNode().put("status", "SUCCEEDED").set("data", data);
    }

    private ObjectNode outlineData(UUID document, String snapshot, String... headings) {
        ObjectNode data = json.createObjectNode()
                .put("documentId", document.toString()).put("snapshotId", snapshot)
                .put("processingStatus", "READY").put("structure", "HEURISTIC_HEADINGS").put("truncated", false);
        var sections = data.putArray("sections");
        for (int index = 0; index < headings.length; index++) {
            sections.addObject().put("heading", headings[index])
                    .put("from_chunk", index).put("through_chunk", index);
        }
        return data;
    }

    private ObjectNode readData(UUID document, String snapshot, String heading) {
        ObjectNode data = json.createObjectNode()
                .put("documentId", document.toString()).put("snapshotId", snapshot)
                .put("processingStatus", "READY").put("readChars", 120)
                .put("coverage", "SPECIFIED_RANGE_ONLY").put("truncated", false).put("hasMore", false);
        data.putArray("items").addObject().put("chunkId", UUID.randomUUID().toString())
                .put("chunkNo", 0).put("heading", heading).put("content", "正文内容");
        data.putNull("continuation");
        return data;
    }

    private JsonNode collectedCoverage(UUID parentRunId) {
        String raw = jdbc.queryForObject("""
                SELECT (output_json->'coverage')::text FROM agent_step
                WHERE run_id=? AND type='DELEGATION_COMPLETED'
                """, String.class, parentRunId);
        assertThat(raw).as("DELEGATION_COMPLETED must carry structured coverage").isNotNull();
        try {
            return json.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new AssertionError("coverage 不是合法 JSON", malformed);
        }
    }

    /**
     * 覆盖事实由子运行持久化工具结果推导，随 DELEGATION_COMPLETED 回收：
     * 包含文档/版本身份、提纲取得状态与可信度、已读章节、结束原因——不由模型填写。
     */
    @Test
    void delegationCoverageIsDerivedFromChildPersistedToolResults() {
        AgentRunView parent = newParentWithClaim("w-cov");
        AgentRunView child = delegateChild(parent);
        child = claimChild(parent, child, "w-cov-child");
        UUID document = UUID.randomUUID();
        String snapshot = UUID.randomUUID().toString();
        child = repository.recordToolResult(child, "get_document_outline", json.createObjectNode(),
                toolOutput(outlineData(document, snapshot, "架构概述", "评分算法")), false);
        child = repository.recordToolResult(child, "read_document_section", json.createObjectNode(),
                toolOutput(readData(document, snapshot, "架构概述")), false);
        repository.recordFinal(child, "子运行研究结论", List.of());

        JsonNode coverage = collectedCoverage(parent.id());
        assertThat(coverage.path("coverageKnown").asBoolean()).isTrue();
        assertThat(coverage.path("endReason").asText()).isEqualTo("SUCCEEDED");
        JsonNode doc = coverage.path("documents").get(0);
        assertThat(doc.path("documentId").asText()).isEqualTo(document.toString());
        assertThat(doc.path("snapshotId").asText()).isEqualTo(snapshot);
        assertThat(doc.path("processingStatus").asText()).isEqualTo("READY");
        assertThat(doc.path("outline").path("status").asText()).isEqualTo("OBTAINED");
        assertThat(doc.path("outline").path("structure").asText()).isEqualTo("HEURISTIC_HEADINGS");
        assertThat(doc.path("outline").path("trust").asText()).isEqualTo("HEURISTIC");
        assertThat(doc.path("outline").path("sectionsListed").asInt()).isEqualTo(2);
        assertThat(doc.path("sectionsRead").path("count").asInt()).isEqualTo(1);
        // 提纲列出 2 节但只读 1 节：缺口如实给出，不掩盖
        assertThat(coverage.path("gaps").toString()).contains("提纲列出 2 节").contains("未读：评分算法");
    }

    /**
     * 父综合请求把已校验覆盖事实与 UNTRUSTED 研究文字分开注入：覆盖块在
     * UNTRUSTED 正文块之外，父运行据此说明真实范围，不再只凭子运行的文字转述
     * （真实 B-narrow2 实验：子运行已取得提纲并读完 4 节，父综合却称"未取得提纲"）。
     */
    @Test
    void parentSynthesisReceivesVerifiedCoverageSeparateFromUntrustedChildText() {
        AgentRunView parent = newParentWithClaim("w-cov-inject");
        AgentRunView child = delegateChild(parent);
        child = claimChild(parent, child, "w-cov-inject-child");
        UUID document = UUID.randomUUID();
        String snapshot = UUID.randomUUID().toString();
        child = repository.recordToolResult(child, "get_document_outline", json.createObjectNode(),
                toolOutput(outlineData(document, snapshot, "架构概述", "评分算法")), false);
        child = repository.recordToolResult(child, "read_document_section", json.createObjectNode(),
                toolOutput(readData(document, snapshot, "架构概述")), false);
        child = repository.recordToolResult(child, "read_document_section", json.createObjectNode(),
                toolOutput(readData(document, snapshot, "评分算法")), false);
        repository.recordFinal(child, "子运行已读完两节正文", List.of());

        var claimed = repository.claimNext("w-cov-inject-parent", java.time.OffsetDateTime.now(),
                Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        parentResponses.clear();
        AgentWorkerOutcome outcome = coordinator.advance(parent);
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);

        String request = modelRequests.get(modelRequests.size() - 1);
        assertThat(request).contains("CHILD_RESEARCH").contains("子运行已读完两节正文");
        assertThat(request).contains("CHILD_RESEARCH_COVERAGE").contains("提纲：已取得").contains("已读 2 节");
        assertThat(request.indexOf("</CHILD_RESEARCH>")).as("覆盖块必须在 UNTRUSTED 正文块之外")
                .isLessThan(request.indexOf("<CHILD_RESEARCH_COVERAGE"));
    }

    /**
     * 旧回收记录没有 coverage 元数据：按"未知"注入，父上下文不得把它解释成
     * "未取得提纲"或"未读任何章节"。
     */
    @Test
    void legacyDelegationRecordWithoutCoverageIsInjectedAsUnknown() {
        AgentRunView parent = newParentWithClaim("w-legacy-cov");
        AgentRunView child = delegateChild(parent);
        // 子运行置终态（旧记录场景），DELEGATION_COMPLETED 为旧格式：无 coverage 字段
        jdbc.update("UPDATE agent_run SET status='SUCCEEDED',finished_at=now() WHERE id=?", child.id());
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,100,'DELEGATION_COMPLETED',?::jsonb,'Specialist child run completed')
                """, parent.id(), "{\"childRunId\":\"" + child.id()
                        + "\",\"status\":\"SUCCEEDED\",\"content\":\"旧记录研究结论\",\"citations\":[]}");
        var claimed = repository.claimNext("w-legacy-cov-parent", java.time.OffsetDateTime.now(),
                Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        parentResponses.clear();
        assertThat(coordinator.advance(parent).status()).isEqualTo(AgentRunStatus.SUCCEEDED);

        String request = modelRequests.get(modelRequests.size() - 1);
        assertThat(request).contains("旧记录研究结论").contains("覆盖事实：未知");
        assertThat(request).as("缺元数据必须按未知兼容，不得解释成未取得提纲")
                .doesNotContain("未取得提纲");
    }

    /** 重复回收/迟到重放：覆盖记录唯一且稳定，不重复回收、不二次写入。 */
    @Test
    void repeatedChildCollectionKeepsSingleCoverageRecord() {
        AgentRunView parent = newParentWithClaim("w-cov-idem");
        AgentRunView child = delegateChild(parent);
        child = claimChild(parent, child, "w-cov-idem-child");
        child = repository.recordToolResult(child, "get_document_outline", json.createObjectNode(),
                toolOutput(outlineData(UUID.randomUUID(), UUID.randomUUID().toString(), "架构概述")), false);
        repository.recordFinal(child, "结论", List.of());
        var stale = child;
        org.assertj.core.api.Assertions.assertThatCode(() -> repository.recordFinal(stale, "结论", List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM agent_step WHERE run_id=? AND type='DELEGATION_COMPLETED'
                """, Integer.class, parent.id())).isEqualTo(1);
        assertThat(collectedCoverage(parent.id()).path("documents").get(0).path("outline").path("status").asText())
                .isEqualTo("OBTAINED");
    }

    /**
     * 四文档降级样本（仅提纲级证据）：覆盖事实必须如实标出"已取得提纲但未读取任何正文"，
     * 父上下文不得把提纲级证据说成正文完整覆盖。
     */
    @Test
    void outlineOnlyCoverageIsReportedAsIncompleteBodyCoverage() {
        AgentRunView parent = newParentWithClaim("w-outline-only");
        AgentRunView child = delegateChild(parent);
        child = claimChild(parent, child, "w-outline-only-child");
        for (int index = 0; index < 4; index++) {
            child = repository.recordToolResult(child, "get_document_outline", json.createObjectNode(),
                    toolOutput(outlineData(UUID.randomUUID(), UUID.randomUUID().toString(),
                            "第一节", "第二节")), false);
        }
        repository.recordFinal(child, "仅提纲级研究发现", List.of());

        JsonNode coverage = collectedCoverage(parent.id());
        assertThat(coverage.path("documents")).hasSize(4);
        for (JsonNode doc : coverage.path("documents")) {
            assertThat(doc.path("outline").path("status").asText()).isEqualTo("OBTAINED");
            assertThat(doc.path("sectionsRead").path("count").asInt()).isZero();
        }
        assertThat(coverage.path("gaps").toString()).contains("已取得提纲但未读取任何正文");

        var claimed = repository.claimNext("w-outline-only-parent", java.time.OffsetDateTime.now(),
                Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        parentResponses.clear();
        assertThat(coordinator.advance(parent).status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        String request = modelRequests.get(modelRequests.size() - 1);
        assertThat(request).contains("CHILD_RESEARCH_COVERAGE");
        assertThat(request).contains("已读 0 节").contains("已取得提纲但未读取任何正文");
    }

    // ===== 2026-10-07 第五轮：可预期受理拒绝后的父运行保留 + 预算兜底覆盖事实 =====

    /**
     * 让父运行在<b>执行</b>受理判定时必然拒绝委派，而请求准备时仍可受理：
     * 剩余推进步 5（12−7），扣除父综合收尾预留 2 后为 3 —— 恰好满足子运行最小步数，
     * 请求准备阶段委派工具仍被暴露；本模型轮次自身消耗 1 步后剩余 4，切分结果降到 2，
     * 执行阶段明确拒绝受理。这正是真实单文档实验的形态（暴露在前、拒绝在后）。
     */
    private AgentRunView parentWithDelegationBudgetExhausted(String worker) {
        AgentRunView parent = newParentWithClaim(worker);
        jdbc.update("UPDATE agent_run SET steps_used=max_steps-5 WHERE id=?", parent.id());
        return repository.findRun(parent.projectId(), parent.id()).orElseThrow();
    }

    /** 让父运行的委派次数先用完（children_used=max_children），预算其余维度充足。 */
    private AgentRunView parentWithDelegationCountExhausted(String worker) {
        AgentRunView parent = newParentWithClaim(worker);
        jdbc.update("UPDATE agent_run SET children_used=max_children WHERE id=?", parent.id());
        return repository.findRun(parent.projectId(), parent.id()).orElseThrow();
    }

    /**
     * 交付 A 的核心红绿用例（真实生产组件：协调器 + 执行器 + 真实 PostgreSQL）：
     * 父运行在综合前再次请求委派、被预算拒绝受理时，<b>不得</b>整轮 FAILED 零回答。
     *
     * <p>修复前：{@code executeDelegation} 把受理拒绝交给 {@code failWriteProposal}，
     * 后者直接 {@code recordFailure} → 运行终态 FAILED（真实单文档实验复现）。
     * 修复后：拒绝结果按真实调用身份持久化，父运行重新排队并在下一次准入
     * 进入无工具综合，交付已有产出。</p>
     */
    @Test
    void expectedDelegationRejectionMustNotFailParentRunWithZeroAnswer() {
        AgentRunView parent = parentWithDelegationBudgetExhausted("w-rej-a");
        // 继续收紧到"剩余 4 步"：此时子可切出的步数 = 12 − 8 − 2 = 2 < 子最小 3，
        // 受理判定确定性地拒绝（剩余 5 步时恰好等于最小 3，会被受理）。
        jdbc.update("UPDATE agent_run SET steps_used=max_steps-4 WHERE id=?", parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        // 父运行已有可信证据：一次成功工具结果（综合依据）
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,tool_name,output_json,reason)
                VALUES (?,1,'TOOL_CALL_COMPLETED','search_project_knowledge',?::jsonb,'TOOL_SUCCESS')
                """, parent.id(), "{\"status\":\"SUCCEEDED\",\"data\":{\"items\":[{\"filename\":\"验收标准v2.1.md\"}]}}");

        // 受理边界始终返回类型化的稳定原因码（不依赖异常消息文本），
        // 且不创建子运行、不改写运行状态：这是"可预期拒绝"与"执行失败"的分界。
        // 先探测受理边界，避免被后续推进改变 steps_used 而影响结论。
        AgentRunView probe = parent;
        var thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                repository.documentResearchDelegationResult(probe, UUID.randomUUID().toString(),
                        "Research project documents with reliable sources"));
        assertThat(thrown)
                .isInstanceOf(com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException.class);
        assertThat(((com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException) thrown)
                .reasonCode()).isEqualTo("AGENT_DELEGATION_BUDGET_INSUFFICIENT");
        assertThat(repository.childRuns(parent.projectId(), parent.id())).isEmpty();
        assertThat(repository.findRun(parent.projectId(), parent.id()).orElseThrow().status())
                .as("可预期拒绝不得把父运行判成终态").isEqualTo(AgentRunStatus.RUNNING);

        // F6 修复后的行为：该父运行剩余不足以容下"子最小研究 3 步 + 父综合收尾 2 步"时，
        // 委派工具<b>不再暴露</b>，而不是先暴露、等模型请求后再拒绝（白耗一次模型轮次）。
        // 这比"拒绝但不失败"更强：注定被拒的动作从一开始就不在可见列表里。
        // 该轮本身也检验交付：已有可信证据 → 无工具综合，正常收口且有真实回答（不得零交付）。
        exposedToolNames.clear();
        parentResponses.clear();
        AgentWorkerOutcome delivered = coordinator.advance(
                repository.findRun(parent.projectId(), parent.id()).orElseThrow());
        assertThat(exposedToolNames).isNotEmpty();
        assertThat(exposedToolNames.get(exposedToolNames.size() - 1))
                .as("剩余额度容不下子研究与父收尾时不得暴露委派工具")
                .doesNotContain("delegate_document_research");
        assertThat(delivered.status())
                .as("可预期的受理拒绝不得把父运行判成 FAILED")
                .isNotEqualTo(AgentRunStatus.FAILED);
        assertThat(delivered.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(delivered.answer()).as("不得零交付").isNotNull().isNotBlank();
    }

    /** 本轮模型请求实际暴露给模型的工具名（可见性断言用）；会推进一次模型轮。 */
    private List<String> exposedToolNamesForTurn(AgentRunView parent) {
        exposedToolNames.clear();
        parentResponses.clear();
        parentResponses.add(parentTurn(List.of()));
        coordinator.advance(parent);
        return exposedToolNames.isEmpty() ? List.of() : exposedToolNames.get(exposedToolNames.size() - 1);
    }

    /**
     * 委派次数耗尽同样属于可预期受理拒绝，且与预算不足是<b>可区分</b>的稳定原因码。
     * 次数耗尽时工具可见性已被收窄（不可能受理的委派不再暴露），因此这里通过受理边界
     * （repository 的持久化受理事务）验证类型化原因，并验证运行未被收口为终态。
     */
    @Test
    void exhaustedDelegationCountIsDistinctExpectedRejection() {
        AgentRunView parent = parentWithDelegationCountExhausted("w-rej-count");
        var thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                repository.documentResearchDelegationResult(parent, UUID.randomUUID().toString(),
                        "Research project documents with reliable sources"));

        assertThat(thrown)
                .as("次数耗尽是类型化的可预期受理拒绝")
                .isInstanceOf(com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException.class);
        assertThat(((com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException) thrown)
                .reasonCode()).isEqualTo("AGENT_DELEGATION_CHILDREN_EXHAUSTED");
        assertThat(repository.childRuns(parent.projectId(), parent.id())).isEmpty();
        // 受理被拒不改写运行状态：父运行保持可继续
        assertThat(repository.findRun(parent.projectId(), parent.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.RUNNING);

        // 工具可见性收窄：次数已耗尽时下一次请求不再暴露委派工具（避免反复请求注定被拒的委派）
        // 让当前 claim 的租约过期以便重新领取（不影响已用额度与 children_used）
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()-interval '1 minute' WHERE id=?", parent.id());
        var claimed = repository.claimNext("w-rej-count-2", java.time.OffsetDateTime.now(),
                Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        parentResponses.add(parentTurn(List.of(new ModelToolCall("count-search", "list_project_documents",
                json.createObjectNode()))));
        coordinator.advance(repository.findRun(parent.projectId(), parent.id()).orElseThrow());
        assertThat(exposedToolNames).isNotEmpty();
        assertThat(exposedToolNames.get(exposedToolNames.size() - 1))
                .doesNotContain("delegate_document_research");
    }

    /**
     * 不被软化的 {@code AGENT_TOOL_NOT_ALLOWED}：受理边界上只有"可预期受理拒绝"是类型化异常；
     * 其他同错误码的拒绝（如 depth 边界"子运行不能再委派"）仍是普通 BusinessException，
     * 不会因为本轮软化而被一律放行——证明区分依据是类型而不是错误码或消息文本。
     */
    @Test
    void nonExpectedToolNotAllowedIsNotTypedAsAdmissionRejection() {
        AgentRunView parent = newParentWithClaim("w-depth");
        AgentRunView child = delegateChild(parent);

        // depth 边界：子运行不能再委派（同一 AGENT_TOOL_NOT_ALLOWED 错误码，但非受理拒绝类型）
        var depthRejection = org.assertj.core.api.Assertions.catchThrowable(() ->
                repository.documentResearchDelegationResult(child, UUID.randomUUID().toString(),
                        "Nested delegation attempt on a child run"));
        assertThat(depthRejection)
                .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class)
                .isNotInstanceOf(com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException.class);
        assertThat(((com.shitulelv.aicollab.common.exception.BusinessException) depthRejection).getErrorCode().name())
                .isEqualTo("AGENT_TOOL_NOT_ALLOWED");
        assertThat(repository.childRuns(parent.projectId(), child.id())).isEmpty();
    }

    /**
     * 拒绝后本模型轮次被正确消费：恢复（同一运行重新领取）不重复执行拒绝、不重复计数、
     * 不再创建子运行、不重复落第二条拒绝步骤。
     */
    @Test
    void rejectedDelegationIsConsumedOnceAndNotReplayedOnRecovery() {
        AgentRunView parent = parentWithDelegationBudgetExhausted("w-rej-idem");
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,tool_name,output_json,reason)
                VALUES (?,1,'TOOL_CALL_COMPLETED','search_project_knowledge',?::jsonb,'TOOL_SUCCESS')
                """, parent.id(), "{\"status\":\"SUCCEEDED\",\"data\":{\"items\":[{\"filename\":\"验收标准v2.1.md\"}]}}");
        parentResponses.add(parentTurn(List.of(new ModelToolCall("rej-idem", "delegate_document_research",
                json.createObjectNode().put("objective", "Research project documents with reliable sources")))));
        coordinator.advance(parent);

        int delegationSteps = jdbc.queryForObject("""
                SELECT count(*) FROM agent_step WHERE run_id=? AND tool_name='delegate_document_research'
                """, Integer.class, parent.id());
        int toolCallsUsed = jdbc.queryForObject("SELECT tool_calls_used FROM agent_run WHERE id=?",
                Integer.class, parent.id());
        assertThat(delegationSteps).isEqualTo(1);

        // 恢复：重新领取并按持久化事实推进——该轮次已消费，不再重放委派调用
        var claimed = repository.claimNext("w-rej-idem-2", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        parentResponses.clear();
        AgentWorkerOutcome resumed = coordinator.advance(
                repository.findRun(parent.projectId(), parent.id()).orElseThrow());

        assertThat(resumed.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM agent_step WHERE run_id=? AND tool_name='delegate_document_research'
                """, Integer.class, parent.id()))
                .as("恢复不得重复执行被拒绝的委派").isEqualTo(delegationSteps);
        assertThat(jdbc.queryForObject("SELECT tool_calls_used FROM agent_run WHERE id=?",
                Integer.class, parent.id()))
                .as("恢复不得重复计数").isEqualTo(toolCallsUsed);
        assertThat(repository.childRuns(parent.projectId(), parent.id()))
                .as("恢复不得创建第二个子运行").isEmpty();
    }

    /**
     * 无现成产出但目标仍可执行：拒绝后父运行按既有权限与预算继续，而不是直接兜底终止
     * （无证据时不得伪造答案，也不无限重排队）。
     */
    @Test
    void rejectionWithoutEvidenceKeepsRunExecutableWithinExistingBudget() {
        AgentRunView parent = parentWithDelegationBudgetExhausted("w-rej-cont");
        parentResponses.add(parentTurn(List.of(new ModelToolCall("rej-cont", "delegate_document_research",
                json.createObjectNode().put("objective", "Research project documents with reliable sources")))));
        AgentWorkerOutcome rejected = coordinator.advance(parent);
        assertThat(rejected.status()).isEqualTo(AgentRunStatus.QUEUED);

        // 下一次准入：无证据 → 不是无工具总结，而是继续正常请求（模型可改用其它工具）；
        // 委派工具因剩余预算不足已不可见，模型改调检索
        var claimed = repository.claimNext("w-rej-cont-2", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        parentResponses.clear();
        parentResponses.add(parentTurn(List.of(new ModelToolCall("cont-search", "list_project_documents",
                json.createObjectNode()))));
        int before = modelRequests.size();
        AgentWorkerOutcome continued = coordinator.advance(
                repository.findRun(parent.projectId(), parent.id()).orElseThrow());

        assertThat(continued.status()).as("拒绝后按既有预算继续推进，而不是零回答失败")
                .isEqualTo(AgentRunStatus.QUEUED);
        assertThat(modelRequests.size()).as("确实发出了下一次真实模型请求").isGreaterThan(before);
        assertThat(repository.childRuns(parent.projectId(), parent.id())).isEmpty();
        // 恢复/再次领取不重复拒绝同一调用
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM agent_step WHERE run_id=? AND tool_name='delegate_document_research'
                """, Integer.class, parent.id())).isEqualTo(1);
        // 继续执行的工具确实落库（不是空转重排队）
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM agent_step WHERE run_id=? AND tool_name='list_project_documents'
                """, Integer.class, parent.id())).isEqualTo(1);
    }

    /** 拒绝后不再向模型暴露已不可能受理的委派工具（避免反复请求注定被拒的委派）。 */
    @Test
    void rejectedDelegationToolIsNoLongerExposed() {
        AgentRunView parent = parentWithDelegationBudgetExhausted("w-rej-vis");
        parentResponses.add(parentTurn(List.of(new ModelToolCall("rej-vis", "delegate_document_research",
                json.createObjectNode().put("objective", "Research project documents with reliable sources")))));
        coordinator.advance(parent);

        var claimed = repository.claimNext("w-rej-vis-2", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(parent.id());
        parentResponses.clear();
        parentResponses.add(parentTurn(List.of(new ModelToolCall("vis-search", "search_project_knowledge",
                json.createObjectNode().put("query", "acceptance criteria")))));
        coordinator.advance(repository.findRun(parent.projectId(), parent.id()).orElseThrow());

        assertThat(exposedToolNames).isNotEmpty();
        assertThat(exposedToolNames.get(exposedToolNames.size() - 1))
                .as("剩余预算容不下子运行时不得再暴露委派工具")
                .doesNotContain("delegate_document_research");
    }

    /**
     * 权限/策略边界不得被受理拒绝的软处理吞掉：定时运行不暴露委派工具，
     * 强行请求时的拒绝必须是权限语义（TOOL_NOT_ALLOWED），不带受理拒绝标记，
     * 也不创建子运行。
     */
    @Test
    void permissionFailureStillKeepsOriginalFailureBoundary() {
        AgentRunView parent = newParentWithClaim("w-perm");
        // 定时运行不允许委派：工具策略不通过（TOOL_NOT_ALLOWED），属权限/安全边界
        jdbc.update("UPDATE agent_run SET scheduled=true WHERE id=?", parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        parentResponses.add(parentTurn(List.of(new ModelToolCall("perm-delegate", "delegate_document_research",
                json.createObjectNode().put("objective", "Research project documents with reliable sources")))));
        coordinator.advance(parent);

        assertThat(repository.childRuns(parent.projectId(), parent.id()))
                .as("权限拒绝不得创建子运行").isEmpty();
        String output = jdbc.queryForObject("""
                SELECT output_json::text FROM agent_step
                WHERE run_id=? AND tool_name='delegate_document_research' ORDER BY sequence_no DESC LIMIT 1
                """, String.class, parent.id());
        assertThat(output).as("权限边界必须保持权限语义")
                .contains("TOOL_NOT_ALLOWED")
                .doesNotContain("AGENT_DELEGATION_BUDGET_INSUFFICIENT")
                .doesNotContain("AGENT_DELEGATION_CHILDREN_EXHAUSTED")
                .doesNotContain("delegationAdmitted");
    }

    /** 暂停仍保持原边界：暂停意图先落库时不启动委派，也不被拒绝处理自动解除暂停。 */
    @Test
    void pauseStillWinsOverDelegationRejectionHandling() {
        AgentRunView parent = newParentWithClaim("w-rej-pause");
        jdbc.update("UPDATE agent_run SET pause_requested_at=now() WHERE id=?", parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();
        AgentWorkerOutcome outcome = coordinator.advance(parent);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(repository.findRun(parent.projectId(), parent.id()).orElseThrow().status())
                .as("暂停不得被拒绝处理自动解除")
                .isEqualTo(AgentRunStatus.PAUSED);
    }

    /**
     * 交付 B：预算证据兜底必须携带已校验覆盖事实。
     * 修复前兜底只用子运行文字产出，父回答可能把已取得提纲说成未取得。
     */
    @Test
    void budgetFallbackMustCarryVerifiedCoverageFacts() {
        AgentRunView parent = newParentWithClaim("w-fb-cov");
        UUID document = UUID.randomUUID();
        String snapshot = UUID.randomUUID().toString();
        // 已回收子运行产出：文字结论 + 结构化覆盖事实（提纲已取得、读完 2 节）
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,1,'DELEGATION_COMPLETED',?::jsonb,'Specialist child run completed')
                """, parent.id(), """
                {"childRunId":"22222222-2222-2222-2222-222222222222","status":"SUCCEEDED",
                 "content":"子运行已取得提纲并读完两节正文。","citations":[],
                 "coverage":{"coverageKnown":true,"endReason":"SUCCEEDED","documents":[
                   {"documentId":"%s","snapshotId":"%s","processingStatus":"READY",
                    "outline":{"status":"OBTAINED","structure":"HEURISTIC_HEADINGS","trust":"HEURISTIC",
                               "sectionsListed":2,"truncated":false,"failures":0},
                    "sectionsRead":{"count":2,"headings":["架构概述","评分算法"],"truncated":false,
                                    "unreadRangeUnknown":false}}],
                  "limits":[],"gaps":[],"notes":["HEURISTIC_HEADINGS 提纲由标题识别得出，不是保证完整的目录；取得提纲不等于读完全文。"]}}
                """.formatted(document, snapshot));
        // 父运行步骤预算逼近终点（剩余 1 步 < 收尾所需 2 步 → EXHAUSTED，走证据兜底）
        jdbc.update("UPDATE agent_run SET steps_used=max_steps-1 WHERE id=?", parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();

        AgentWorkerOutcome outcome = coordinator.advance(parent);
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        String answer = outcome.answer();
        assertThat(answer).as("兜底仍交付子运行文字产出").contains("子运行已取得提纲并读完两节正文");
        assertThat(answer).as("兜底必须携带已校验覆盖事实块")
                .contains("已校验覆盖事实").contains("22222222-2222-2222-2222-222222222222");
        assertThat(answer).as("已取得提纲不得被误称未取得")
                .contains(document.toString()).contains("提纲：已取得").contains("已读 2 节");
        assertThat(answer).contains("启发式标题识别，不代表完整目录");
        assertThat(answer).as("覆盖事实优先于子运行文字").contains("以此为准");
    }

    /** 旧回收记录缺 coverage：兜底按"未知"兼容，不得解释成"未取得提纲"。 */
    @Test
    void budgetFallbackTreatsLegacyRecordWithoutCoverageAsUnknown() {
        AgentRunView parent = newParentWithClaim("w-fb-legacy");
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,1,'DELEGATION_COMPLETED',?::jsonb,'Specialist child run completed')
                """, parent.id(), """
                {"childRunId":"33333333-3333-3333-3333-333333333333","status":"SUCCEEDED",
                 "content":"旧格式子运行结论。","citations":[]}
                """);
        jdbc.update("UPDATE agent_run SET steps_used=max_steps-1 WHERE id=?", parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();

        AgentWorkerOutcome outcome = coordinator.advance(parent);
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.answer()).contains("旧格式子运行结论").contains("覆盖事实：未知");
        assertThat(outcome.answer()).as("旧记录不得被解释成未取得提纲")
                .doesNotContain("未取得提纲");
    }

    /** 提纲级（局部）覆盖：兜底如实说明"已取得提纲但未读取任何正文"，不冒充正文覆盖。 */
    @Test
    void budgetFallbackReportsOutlineOnlyCoverageHonestly() {
        AgentRunView parent = newParentWithClaim("w-fb-outline");
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,1,'DELEGATION_COMPLETED',?::jsonb,'Specialist child run completed')
                """, parent.id(), """
                {"childRunId":"44444444-4444-4444-4444-444444444444","status":"SUCCEEDED",
                 "content":"仅完成提纲读取。","citations":[],
                 "coverage":{"coverageKnown":true,"endReason":"SUCCEEDED","documents":[
                   {"documentId":"55555555-5555-5555-5555-555555555555","snapshotId":null,
                    "processingStatus":"READY",
                    "outline":{"status":"OBTAINED","structure":"HEURISTIC_HEADINGS","trust":"HEURISTIC",
                               "sectionsListed":2,"truncated":false,"failures":0},
                    "sectionsRead":{"count":0,"headings":[],"truncated":false,"unreadRangeUnknown":false}}],
                  "limits":[],"gaps":["文档 55555555-5555-5555-5555-555555555555：已取得提纲但未读取任何正文"],
                  "notes":[]}}
                """);
        jdbc.update("UPDATE agent_run SET steps_used=max_steps-1 WHERE id=?", parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();

        AgentWorkerOutcome outcome = coordinator.advance(parent);
        assertThat(outcome.answer())
                .contains("已读 0 节").contains("已取得提纲但未读取任何正文")
                .doesNotContain("正文完整覆盖");
    }

    /** 子运行文字与覆盖事实冲突时以覆盖事实为准：文字不得覆盖已校验覆盖声明。 */
    @Test
    void verifiedCoverageFactsWinOverChildTextClaims() {
        AgentRunView parent = newParentWithClaim("w-fb-conflict");
        UUID document = UUID.randomUUID();
        // 子文字声称"未取得提纲"，而持久化覆盖事实是"提纲已取得且已读 3 节"
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,1,'DELEGATION_COMPLETED',?::jsonb,'Specialist child run completed')
                """, parent.id(), """
                {"childRunId":"66666666-6666-6666-6666-666666666666","status":"SUCCEEDED",
                 "content":"说明：本次未取得文档提纲，因此未能读取任何正文。","citations":[],
                 "coverage":{"coverageKnown":true,"endReason":"SUCCEEDED","documents":[
                   {"documentId":"%s","snapshotId":null,"processingStatus":"READY",
                    "outline":{"status":"OBTAINED","structure":"HEURISTIC_HEADINGS","trust":"HEURISTIC",
                               "sectionsListed":3,"truncated":false,"failures":0},
                    "sectionsRead":{"count":3,"headings":["一","二","三"],"truncated":false,
                                    "unreadRangeUnknown":false}}],
                  "limits":[],"gaps":[],"notes":[]}}
                """.formatted(document));
        jdbc.update("UPDATE agent_run SET steps_used=max_steps-1 WHERE id=?", parent.id());
        parent = repository.findRun(parent.projectId(), parent.id()).orElseThrow();

        AgentWorkerOutcome outcome = coordinator.advance(parent);
        String answer = outcome.answer();
        // 覆盖事实与文字同时如实呈现，且明确以覆盖事实为准（不静默丢弃任一侧）
        assertThat(answer).contains(document.toString()).contains("提纲：已取得").contains("已读 3 节");
        assertThat(answer).as("必须显式声明覆盖事实优先于子运行文字")
                .contains("以此为准");
    }
}

