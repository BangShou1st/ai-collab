package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                new AgentContextProperties(true, 20_000, 4_000, 2_000, java.util.Map.of()),
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
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                "结合需求文档分析项目风险", false, null, null);
        ClaimedAgentRun claimed = recovery.claim("w1", Duration.ofMinutes(6)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(run.id());
        run = repository.findRun(fixture.project(), run.id()).orElseThrow();

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
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                "研究文档", false, null, null);
        // 受理发生在工具执行阶段：运行必须处于 RUNNING（先领取）
        recovery.claim("w-i1", Duration.ofMinutes(6)).orElseThrow();
        run = repository.findRun(fixture.project(), run.id()).orElseThrow();
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
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                "研究文档", false, null, null);
        recovery.claim("w-p0", Duration.ofMinutes(6)).orElseThrow();
        run = repository.findRun(fixture.project(), run.id()).orElseThrow();
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

    private AgentRunView newParentWithClaim(String worker) {
        Fixture fixture = fixture();
        AgentRunView run = repository.createRun(fixture.project(), fixture.session(), fixture.user(),
                "Research the project documents and report reliable findings", false, null, null);
        repository.claimNext(worker, java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
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
        // tool_calls_used 含委派受理自身消耗（R8 修复）+ 子运行回收
        assertThat(after.stepsUsed()).isEqualTo(4);
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
}
