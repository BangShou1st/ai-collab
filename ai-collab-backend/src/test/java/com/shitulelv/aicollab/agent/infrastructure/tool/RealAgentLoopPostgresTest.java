package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.runtime.AgentCancellationService;
import com.shitulelv.aicollab.agent.application.runtime.AgentContextAssembler;
import com.shitulelv.aicollab.agent.application.runtime.AgentContextProperties;
import com.shitulelv.aicollab.agent.application.runtime.AgentContextSummarizer;
import com.shitulelv.aicollab.agent.application.runtime.AgentConvergencePolicy;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.runtime.AgentLoopGuard;
import com.shitulelv.aicollab.agent.application.runtime.AgentModelConfigurationStore;
import com.shitulelv.aicollab.agent.application.runtime.AgentModelMessageComposer;
import com.shitulelv.aicollab.agent.application.runtime.AgentPlanService;
import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.application.runtime.AgentToolCallExecutor;
import com.shitulelv.aicollab.agent.application.runtime.AgentToolScheduler;
import com.shitulelv.aicollab.agent.application.runtime.NativeToolCallingExecutor;
import com.shitulelv.aicollab.agent.application.runtime.RoutingAgentModelExecutor;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.agent.infrastructure.tool.DocumentAgentTools;
import com.shitulelv.aicollab.agent.infrastructure.tool.DocumentResearchDelegateAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.KnowledgeSearchAgentTool;
import com.shitulelv.aicollab.document.application.service.DocumentApplicationService;
import com.shitulelv.aicollab.document.application.service.DocumentContentService;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.application.view.DocumentView;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.infrastructure.ai.model.KiloGatewayModelExecution;
import com.shitulelv.aicollab.infrastructure.ai.model.ExternalProviderFailure;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ProviderPresetRegistry;
import com.shitulelv.aicollab.infrastructure.ai.model.KiloGatewayClientTransport;
import com.shitulelv.aicollab.infrastructure.ai.model.ZenModelExecution;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnGateway;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayAutoConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-RAG-01C 第二轮 阶段 C2：<b>真实 Chat 模型的完整 Agent-loop 验收</b>（Opt-in）。
 *
 * <p>整类由 {@code AI_REAL_AGENT_LOOP=true} 门控：未启用时整类跳过（不联网、不启动容器），
 * 保证普通 CI 不会因缺少外部模型而失败。启用时用<b>生产 Agent 模型路由</b>
 * （{@link RoutingAgentModelExecutor} + {@link NativeToolCallingExecutor}）穿过真实 Kilo 免费模型，
 * 驱动完整循环：用户问题 → 模型决策 → 真实工具调用 → 证据回收 → 最终综合 → 持久化引用。</p>
 *
 * <p><b>真实的部分</b>：真实 PostgreSQL（Flyway 真实 schema）、真实 Agent 持久执行引擎、真实工具实现、
 * 真实正文读取服务、真实引用身份、真实外部模型与真实工具调用协议。
 * <b>测试侧固定的部分</b>：模型供应商被固定为测试选定的 Kilo 免费模型（不绕过 429、不换账号/身份、不用付费模型），
 * 检索后端（{@code DocumentSearchService}）用确定性关键词解析替代向量检索，以保证可复现与有界成本。</p>
 *
 * <p>有界执行：每个场景固定小预算（步数/工具/子运行），并有墙钟上限；上游不可用（429/超时/5xx）
 * 时如实标记 {@code BLOCKED_EXTERNAL}，不伪造工具调用成功，也不把失败当通过。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AI_REAL_AGENT_LOOP", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealAgentLoopPostgresTest {
    private static final String MODEL = System.getenv().getOrDefault(
            "AI_REAL_AGENT_LOOP_MODEL", "nvidia/nemotron-3-super-120b-a12b:free");
    private static final int MAX_MODEL_CALLS_PER_SCENARIO = 12;
    private static final long SCENARIO_TIMEOUT_MS = 180_000;

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
    private AgentApprovalService approvals;
    private RoutingAgentModelExecutor modelExecutor;
    private AgentRuntimeCoordinator coordinator;
    private RealAgentLoopBudget scenarioBudget;
    private ExecutorService providerExecutor;
    private AgentRecoveryJob recovery;
    private DocumentContentService content;
    private DocumentApplicationService documents;

    private Function<String, List<DocumentSearchHit>> searchResolver = query -> List.of();

    private UUID user;
    private UUID project;
    private UUID session;
    private final Map<String, UUID> documentIds = new LinkedHashMap<>();
    private final Map<String, UUID> chunkIds = new LinkedHashMap<>();
    private final AtomicReference<ExternalProviderFailure> supplierFailure = new AtomicReference<>();

    private static final Map<String, Map<String, Object>> SCENARIOS = new LinkedHashMap<>();
    private static final AtomicReference<String> EXTERNAL_BLOCK = new AtomicReference<>();
    static final Path EVIDENCE_PATH = Path.of("target", "retrieval-baseline", "2026-10-19",
            "real-agent-loop.json");

    @BeforeAll
    static void migrate() {
        SCENARIOS.clear();
        EXTERNAL_BLOCK.set(null);
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        recorder = new AgentRunEventRecorder(jdbc, json);
        repository = new AgentRepository(jdbc, json, recorder);
        txManager = new DataSourceTransactionManager(dataSource);
        System.out.println("REAL_AGENT_LOOP_MODEL=" + MODEL);
    }

    @BeforeEach
    void setUp() {
        for (String table : List.of("agent_planning_operation_event", "agent_planning_operation",
                "agent_usage_settlement", "agent_tool_invocation", "agent_step", "agent_run_event",
                "agent_message", "agent_run", "agent_session", "document_body_chunk", "document_body",
                "document_chunk", "project_document", "project_member", "project", "app_user")) {
            jdbc.update("DELETE FROM " + table);
        }
        scenarioBudget = null;
        providerExecutor = null;
        supplierFailure.set(null);
        searchResolver = query -> List.of();

        assembler = mock(AgentContextAssembler.class);
        events = mock(AgentEventService.class);
        approvals = mock(AgentApprovalService.class);
        ReflectionTestUtils.setField(recorder, "events", events);
        recovery = new AgentRecoveryJob(repository);
        when(assembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
            AgentRunView run = invocation.getArgument(0);
            return run == null ? null : context(run);
        });

        fixture();
        seedCorpus();
        modelExecutor = realModelExecutor();
        coordinator = coordinator(registry());
    }

    // ============================================================ 真实 Agent-loop 场景

    @Test
    @Order(1)
    void realModelDrivesSearchThenSynthesizesWithRealCitations() {
        skipAfterExternalBlock("R1");
        searchResolver = query -> List.of(
                hit("上线验收规程.md", 0, "上线门禁",
                        "生产发布前必须完成核心业务流程的自动化验收，所有阻断级缺陷必须关闭。", 0.9),
                hit("发布回滚预案.md", 0, "回滚触发条件",
                        "上线后核心指标连续两次低于约定阈值时，立即暂停发布并回滚到最近一次稳定版本。", 0.88));

        Map<String, Object> result = runScenario("R1-完整循环-搜索并综合",
                "结合项目文档说明上线验收需要满足的条件，以及上线后指标不达标时该如何处理。"
                        + "请调用检索工具获取真实来源后再回答。设定：一个检索工具调用即可。");

        assertRealScenarioSucceeded(result);
        assertRealToolCall(result, "search_project_knowledge");
        assertAnswerContains(result, "自动化验收", "阻断", "连续两次", "回滚");
        assertCitationsAllowed(result, Set.of(
                documentIds.get("上线验收规程.md").toString(),
                documentIds.get("发布回滚预案.md").toString(),
                chunkIds.get("上线验收规程.md#0").toString(),
                chunkIds.get("发布回滚预案.md#0").toString()));
        assertCitationChunksPresent(result, Set.of(
                chunkIds.get("上线验收规程.md#0").toString(),
                chunkIds.get("发布回滚预案.md#0").toString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    @Order(2)
    void realModelAnswersDocumentScopedQuestionWithoutLeakingOtherDocuments() {
        skipAfterExternalBlock("R2");
        searchResolver = query -> List.of(
                hit("批量导出规范.md", 0, "文件保留",
                        "已完成的导出文件在临时存储区保存二十四小时，过期后自动清理。", 0.9),
                hit("数据归档通告.md", 0, "归档周期", "业务记录每季度归档一次，归档副本整体保留五年。", 0.7));

        Map<String, Object> result = runScenario("R2-指定文档范围",
                "根据《批量导出规范》回答：已完成的导出文件会在临时存储区保留多久？请调用检索工具取得真实来源。");

        assertRealScenarioSucceeded(result);
        assertRealToolCall(result, "search_project_knowledge");
        assertAnswerContains(result, "二十四小时|24\\s*小时");
        assertCitationsAllowed(result, Set.of(
                documentIds.get("批量导出规范.md").toString(),
                chunkIds.get("批量导出规范.md#0").toString()));
        assertCitationChunksPresent(result, Set.of(chunkIds.get("批量导出规范.md#0").toString()));
        assertThat((List<String>) result.get("citationIds")).as("R2 不得持久化引用归档通告")
                .doesNotContain(documentIds.get("数据归档通告.md").toString(),
                        chunkIds.get("数据归档通告.md#0").toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    @Order(3)
    void realModelRefusesWhenMaterialIsInsufficient() {
        skipAfterExternalBlock("R3");
        searchResolver = query -> List.of();

        Map<String, Object> result = runScenario("R3-资料不足拒绝编造",
                "项目文档里有没有规定审计日志必须保留多少年？如果没有依据，请如实说明资料不足，不要编造年限。");

        assertRealScenarioSucceeded(result);
        assertRealToolCall(result, "search_project_knowledge");
        assertAnswerContains(result, "资料不足|未找到|无法确定|没有依据");
        assertThat((List<String>) result.get("citationIds"))
                .as("R3 无证据时不得有任何持久化引用").isEmpty();
    }

    // ============================================================ 有界驱动

    private Map<String, Object> runScenario(String name, String goal) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("scenario", name);
        item.put("goal", goal);
        item.put("provider", "KILO_GATEWAY_FREE");
        item.put("model", MODEL);
        long started = System.nanoTime();
        scenarioBudget = new RealAgentLoopBudget(started,
                TimeUnit.MILLISECONDS.toNanos(SCENARIO_TIMEOUT_MS), MAX_MODEL_CALLS_PER_SCENARIO);
        providerExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "real-agent-loop-provider-call");
            thread.setDaemon(true);
            return thread;
        });
        String status = "UNKNOWN";
        Throwable internalFailure = null;
        try {
            AgentRunView run = newRun(goal);
            AgentWorkerOutcome outcome = drive(run);
            status = outcome == null ? "NULL" : outcome.status().name();
            item.put("runStatus", status);
            item.put("finalAnswer", safeAnswer(run));
            List<String> citations = citationsOf(run);
            item.put("citationIds", citations);
            item.put("citationChunkIds", citations.stream().filter(chunkIdSet()::contains).toList());
            item.put("searchToolInvocations", toolInvocations(run, "search_project_knowledge"));
            item.put("toolErrorInvocations", toolInvocationsWithReason(run, "TOOL_ERROR"));
        } catch (ExternalProviderFailure failure) {
            status = "BLOCKED_EXTERNAL";
            item.put("runStatus", status);
            item.put("blockedReason", failure.kind().name());
            item.put("providerStatusCode", failure.statusCode());
            EXTERNAL_BLOCK.compareAndSet(null, failure.kind().name());
        } catch (RuntimeException | Error failure) {
            status = "TEST_FAILED";
            item.put("runStatus", status);
            item.put("failureType", failure.getClass().getName());
            internalFailure = failure;
        } finally {
            if (providerExecutor != null) providerExecutor.shutdownNow();
        }
        item.put("modelCalls", scenarioBudget.modelCalls());
        item.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        assertThat((int) item.get("modelCalls"))
                .as("失败与成功路径的真实模型调用次数都必须服从同一上限")
                .isLessThanOrEqualTo(MAX_MODEL_CALLS_PER_SCENARIO);
        assertThat((long) item.get("elapsedMs"))
                .as("失败与成功路径的总耗时都必须服从同一墙钟上限")
                .isLessThanOrEqualTo(SCENARIO_TIMEOUT_MS);
        SCENARIOS.put(name, item);
        if (internalFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (internalFailure instanceof Error errorFailure) throw errorFailure;
        return item;
    }

    private AgentWorkerOutcome drive(AgentRunView parent) {
        AgentWorkerOutcome last = advanceChecked(repository.findRun(project, parent.id()).orElseThrow());
        for (int round = 0; round < 24; round++) {
            if (allTerminal(parent)) return last;
            scenarioBudget.remainingNanos(System.nanoTime());
            if (scenarioBudget.modelCalls() >= MAX_MODEL_CALLS_PER_SCENARIO) {
                throw new RealAgentLoopBudget.LimitExceededException("scenario model-call limit exceeded");
            }
            var claimed = recovery.claim("w-real-" + round, Duration.ofMinutes(3)).orElse(null);
            if (claimed == null) {
                throw new IllegalStateException("real Agent run did not produce a recoverable step");
            }
            last = advanceChecked(repository.findRun(project, claimed.id()).orElseThrow());
        }
        if (!allTerminal(parent)) {
            throw new RealAgentLoopBudget.LimitExceededException("real Agent loop exceeded the bounded round count");
        }
        return last;
    }

    private AgentWorkerOutcome advanceChecked(AgentRunView run) {
        AgentWorkerOutcome outcome = coordinator.advance(run);
        ExternalProviderFailure external = supplierFailure.get();
        if (external != null) throw external;
        return outcome;
    }

    private boolean allTerminal(AgentRunView parent) {
        boolean parentDone = repository.findRun(project, parent.id())
                .map(view -> view.status().terminal()).orElse(false);
        boolean childrenDone = repository.childRuns(project, parent.id()).stream()
                .allMatch(child -> child.status().terminal());
        return parentDone && childrenDone;
    }

    private AgentRunView newRun(String goal) {
        AgentRunView run = repository.createRun(project, session, user, goal, false, null, null);
        jdbc.update("UPDATE agent_run SET max_steps=6, max_tool_calls=4, max_children=1 WHERE id=?", run.id());
        repository.claimNext("w-real-create", OffsetDateTime.now(), Duration.ofMinutes(3)).orElseThrow();
        return repository.findRun(project, run.id()).orElseThrow();
    }

    static String classifyUpstream(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ExternalProviderFailure external) return external.kind().name();
            cause = cause.getCause();
        }
        return null;
    }

    // ============================================================ 断言辅助

    static boolean isSuccessfulStatus(String status) {
        return "SUCCEEDED".equals(status);
    }

    static boolean assertSuccessfulStatus(Map<String, Object> result) {
        assertThat(result.get("runStatus"))
                .as("%s：业务成功验收必须为 SUCCEEDED", result.get("scenario"))
                .isEqualTo("SUCCEEDED");
        return true;
    }

    private void assertRealScenarioSucceeded(Map<String, Object> result) {
        if ("BLOCKED_EXTERNAL".equals(result.get("runStatus"))) {
            Assumptions.abort("真实模型供应商外部阻断（" + result.get("blockedReason")
                    + "），已记录为未完成并跳过业务成功验收");
        }
        assertSuccessfulStatus(result);
        assertThat((int) result.get("modelCalls"))
                .as("%s：模型调用次数必须有界", result.get("scenario"))
                .isLessThanOrEqualTo(MAX_MODEL_CALLS_PER_SCENARIO);
        assertThat((String) result.get("finalAnswer"))
                .as("%s：成功运行必须持久化真实模型最终输出", result.get("scenario"))
                .isNotBlank();
    }

    @SuppressWarnings("unchecked")
    private void assertRealToolCall(Map<String, Object> result, String tool) {
        List<String> invocations = (List<String>) result.get("searchToolInvocations");
        assertThat(invocations).as("%s：真实模型必须经生产工具路由调用 %s", result.get("scenario"), tool)
                .isNotEmpty();
        assertThat(invocations).as("%s：真实工具调用必须成功（不得把失败当成功）", result.get("scenario"))
                .allMatch(status -> "SUCCEEDED".equals(status));
    }

    private static void assertAnswerContains(Map<String, Object> result, String... regexes) {
        String answer = (String) result.get("finalAnswer");
        for (String regex : regexes) {
            assertThat(answerMatches(answer, regex))
                    .as("%s：最终回答必须包含 /%s/", result.get("scenario"), regex).isTrue();
        }
    }

    static boolean answerMatches(String answer, String regex) {
        return answer != null && java.util.regex.Pattern.compile("(?s).*(?:" + regex + ").*")
                .matcher(answer).matches();
    }

    @SuppressWarnings("unchecked")
    private static void assertCitationsAllowed(Map<String, Object> result, Set<String> allowed) {
        List<String> citations = (List<String>) result.get("citationIds");
        assertThat(citations).as("%s：持久化来源必须满足场景范围", result.get("scenario"))
                .allMatch(allowed::contains);
    }

    @SuppressWarnings("unchecked")
    private static void assertCitationChunksPresent(Map<String, Object> result, Set<String> expected) {
        List<String> chunkCitations = (List<String>) result.get("citationChunkIds");
        assertThat(chunkCitations).as("%s：必要证据分块必须出现在持久化引用中", result.get("scenario"))
                .containsAll(expected);
    }

    private void skipAfterExternalBlock(String scenario) {
        String reason = EXTERNAL_BLOCK.get();
        if (reason == null) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("scenario", scenario);
        item.put("runStatus", "NOT_RUN_AFTER_EXTERNAL_BLOCK");
        item.put("blockedReason", reason);
        item.put("modelCalls", 0);
        item.put("elapsedMs", 0);
        SCENARIOS.put(scenario, item);
        Assumptions.abort("前一真实模型场景已遇到供应商故障 " + reason + "；停止后续外部调用");
    }

    private void assertNoFabricatedCitations(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        List<String> citations = (List<String>) result.get("citationIds");
        Set<String> real = new LinkedHashSet<>();
        for (UUID chunk : chunkIds.values()) real.add(chunk.toString());
        for (UUID document : documentIds.values()) real.add(document.toString());
        assertThat(citations).as("%s：持久化引用必须来自真实种子文档/分块（不得编造）", result.get("scenario"))
                .allMatch(real::contains);
    }

    private Set<String> chunkIdSet() {
        Set<String> chunks = new LinkedHashSet<>();
        for (UUID chunk : chunkIds.values()) chunks.add(chunk.toString());
        return chunks;
    }

    private String safeAnswer(AgentRunView run) {
        try {
            return jdbc.queryForObject("""
                    SELECT content FROM agent_message WHERE run_id=? AND role='ASSISTANT'
                    ORDER BY created_at DESC LIMIT 1
                    """, String.class, run.id());
        } catch (org.springframework.dao.EmptyResultDataAccessException none) {
            return null;
        }
    }

    private List<String> citationsOf(AgentRunView run) {
        String raw = jdbc.queryForObject("""
                SELECT COALESCE(citations_json::text,'[]') FROM agent_message
                WHERE run_id=? AND role='ASSISTANT' ORDER BY created_at DESC LIMIT 1
                """, String.class, run.id());
        List<String> found = new ArrayList<>();
        if (raw == null) return found;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                .matcher(raw);
        while (matcher.find()) found.add(matcher.group());
        return found.stream().distinct().toList();
    }

    private List<String> toolInvocations(AgentRunView run, String tool) {
        return jdbc.queryForList("""
                SELECT COALESCE(output_json->>'status','UNKNOWN') FROM agent_step
                WHERE run_id=? AND type IN ('TOOL_CALL_COMPLETED','TOOL_CALL_FAILED') AND tool_name=?
                """, String.class, run.id(), tool);
    }

    private int toolInvocationsWithReason(AgentRunView run, String reason) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM agent_step WHERE run_id=? AND reason=?
                """, Integer.class, run.id(), reason);
    }

    // ============================================================ 真实模型装配

    /**
     * 生产 Agent 模型路由 + 真实 Kilo 免费模型。供应商在测试侧固定为选定的免费模型
     * （匿名、free-only）；出站 HTTP 由共享库客户端完成。
     */
    private RoutingAgentModelExecutor realModelExecutor() {
        KiloGatewayClient client = realKiloClient();
        var kiloExecution = new KiloGatewayModelExecution(new ProviderPresetRegistry(), json,
                new KiloGatewayClientTransport(client));
        UserAiProvider provider = new UserAiProvider(UUID.randomUUID(), user, "Kilo Gateway Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://ai-gateway.kilo.ai/api/v1", "/chat/completions",
                null, MODEL, true, 0.2, 2000,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING,
                        ModelCapability.STRUCTURED_OUTPUT, ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE),
                false, OffsetDateTime.now(), OffsetDateTime.now(), "KILO_GATEWAY_FREE");

        ModelTurnGateway turnGateway = new ModelTurnGateway() {
            @Override
            public com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult turn(ModelTurnCommand command) {
                long remainingNanos = scenarioBudget.beginModelCall(System.nanoTime());
                Future<com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult> request =
                        providerExecutor.submit(() -> kiloExecution.turn(provider, command));
                try {
                    return request.get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException expired) {
                    request.cancel(true);
                    throw new RealAgentLoopBudget.LimitExceededException(
                            "provider request reached the remaining scenario wall-clock limit");
                } catch (InterruptedException interrupted) {
                    request.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("real Agent-loop test interrupted", interrupted);
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof ExternalProviderFailure external) supplierFailure.set(external);
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IllegalStateException("real provider request failed", cause);
                }
            }
        };
        var nativeExecutor = new NativeToolCallingExecutor(turnGateway);
        var legacyExecutor = mock(com.shitulelv.aicollab.agent.application.runtime.LegacyReadOnlyAgentExecutor.class);
        var zen = mock(ZenModelExecution.class);
        lenient().when(zen.isZen(any())).thenReturn(false);
        var configurationStore = mock(AgentModelConfigurationStore.class);
        lenient().when(configurationStore.require(any())).thenReturn(provider);
        return new RoutingAgentModelExecutor(nativeExecutor, legacyExecutor, zen, configurationStore);
    }

    private static KiloGatewayClient realKiloClient() {
        AtomicReference<KiloGatewayClient> reference = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KiloGatewayAutoConfiguration.class))
                .withPropertyValues("kilo.gateway.request-timeout=90s")
                .run(context -> reference.set(context.getBean(KiloGatewayClient.class)));
        KiloGatewayClient client = reference.get();
        assertThat(client).as("共享库 Kilo 客户端必须可用（kilo.gateway.enabled 默认 true）").isNotNull();
        return client;
    }

    private AgentToolRegistry registry() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        lenient().when(access.requireMember(any(), any())).thenReturn(ProjectRole.OWNER);
        DocumentSearchService search = mock(DocumentSearchService.class);
        when(search.resolveNamedDocumentIds(any(), anyList())).thenAnswer(invocation -> {
            List<String> names = invocation.getArgument(1);
            return documentIds.entrySet().stream()
                    .filter(entry -> names.stream().anyMatch(name -> name.equals(entry.getKey())
                            || name.equals(entry.getKey().replaceFirst("\\.[^.]+$", ""))))
                    .map(Map.Entry::getValue).toList();
        });
        lenient().when(search.search(any(), anyString(), any(), anyInt())).thenAnswer(invocation -> {
            String query = invocation.getArgument(1);
            List<UUID> scope = invocation.getArgument(2);
            List<DocumentSearchHit> hits = searchResolver.apply(query);
            if (scope == null || scope.isEmpty()) return hits;
            return hits.stream().filter(item -> scope.contains(item.documentId())).toList();
        });
        var knowledge = new KnowledgeSearchAgentTool(access, search, new KnowledgeContextBuilder(), json);
        var docs = new DocumentAgentTools();
        var delegate = new DocumentResearchDelegateAgentTool(json);
        return new AgentToolRegistry(List.of(knowledge, docs.listProjectDocuments(this.documents, content, json),
                docs.getDocumentOutline(content), docs.readDocumentSection(content), delegate));
    }

    private AgentRuntimeCoordinator coordinator(AgentToolRegistry registry) {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        lenient().when(access.requireMember(any(), any())).thenReturn(ProjectRole.OWNER);
        content = new DocumentContentService(jdbc, json, access);
        var executor = new AgentToolCallExecutor(repository, registry,
                new AgentCancellationService(repository), new AgentLoopGuard(), approvals, modelExecutor,
                new AgentToolResultSanitizer(json), json, events, new AgentToolScheduler());
        return new AgentRuntimeCoordinator(
                repository, assembler, new AgentSkillRegistry(), new AgentPlanService(repository, json),
                registry, new AgentCancellationService(repository), modelExecutor,
                new AgentConvergencePolicy(), json, events,
                new AgentContextProperties(true, 20_000, 4_000, 2_000, Map.of()),
                new AgentModelMessageComposer(repository, mock(AgentMemoryService.class), json), executor,
                new AgentContextSummarizer(repository, modelExecutor, json));
    }

    private AgentExecutionContext context(AgentRunView run) {
        return new AgentExecutionContext(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(), "OWNER", false,
                AgentPageContext.empty(),
                run.depth() > 0 ? AgentRuntimeLimits.defaults() : AgentRuntimeLimits.forSkill(run.skillCode()),
                run.depth(), List.of());
    }

    // ============================================================ 夹具与种子

    private void fixture() {
        user = UUID.randomUUID();
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "real-" + user.toString().substring(0, 8), "test-only-hash", "RealLoop");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "真实 Agent-loop", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        session = repository.createSession(project, user, "真实 Agent-loop").id();
    }

    private void seedCorpus() {
        seedDocument("上线验收规程.md", List.of(
                chunk("上线门禁", "生产发布前必须完成核心业务流程的自动化验收，所有阻断级缺陷必须关闭。"),
                chunk("验收记录", "验收记录必须包含执行人、执行时间、测试版本与未关闭问题清单。")));
        seedDocument("发布回滚预案.md", List.of(
                chunk("回滚触发条件", "上线后核心指标连续两次低于约定阈值时，立即暂停发布并回滚到最近一次稳定版本。")));
        seedDocument("批量导出规范.md", List.of(
                chunk("文件保留", "已完成的导出文件在临时存储区保存二十四小时，过期后自动清理。")));
        seedDocument("数据归档通告.md", List.of(
                chunk("归档周期", "业务记录每季度归档一次，归档副本整体保留五年。")));

        var views = new ArrayList<DocumentView>();
        OffsetDateTime now = OffsetDateTime.now();
        for (Map.Entry<String, UUID> entry : documentIds.entrySet()) {
            views.add(new DocumentView(entry.getValue(), project, entry.getKey(), entry.getKey(),
                    "text/markdown", 100, DocumentStatus.READY, "markdown", 1, "OLLAMA", "test",
                    1024, null, user, "RealLoop", now, 1, now, now));
        }
        documents = mock(DocumentApplicationService.class);
        lenient().when(documents.list(any(), any())).thenReturn(views);
    }

    private record ChunkSeed(String heading, String content) {}

    private static ChunkSeed chunk(String heading, String content) {
        return new ChunkSeed(heading, content);
    }

    private void seedDocument(String filename, List<ChunkSeed> chunks) {
        UUID document = UUID.randomUUID();
        documentIds.put(filename, document);
        jdbc.update("""
                INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,
                  size_bytes,object_key,status,chunk_count,uploaded_by,indexed_at)
                VALUES (?,?,?,?,'text/markdown',100,?,'READY',?,?,now())
                """, document, project, filename, filename,
                "projects/" + project + "/" + document + ".md", chunks.size(), user);
        jdbc.update("""
                INSERT INTO document_body(document_id,snapshot_id,original_content_hash,parse_version)
                VALUES (?,?,?,'chunk-offset-v2')
                """, document, UUID.randomUUID(), "hash-" + document);
        for (int index = 0; index < chunks.size(); index++) {
            UUID id = UUID.randomUUID();
            chunkIds.put(filename + "#" + index, id);
            jdbc.update("""
                    INSERT INTO document_body_chunk(id,document_id,chunk_no,heading,content,content_hash)
                    VALUES (?,?,?,?,?,?)
                    """, id, document, index, chunks.get(index).heading(), chunks.get(index).content(),
                    "h-" + id);
        }
    }

    private DocumentSearchHit hit(String filename, int chunkNo, String heading, String content,
                                  double similarity) {
        UUID documentId = documentIds.get(filename);
        UUID chunkId = chunkIds.get(filename + "#" + chunkNo);
        return new DocumentSearchHit(chunkId, documentId, filename, heading, content,
                "hash-" + chunkId, similarity, Map.of("pageNumber", chunkNo + 1));
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("note", "D-RAG-01C 第二轮 阶段 C2：真实 Chat 模型的完整 Agent-loop 验收（Opt-in，"
                + "AI_REAL_AGENT_LOOP=true）。通过生产 Agent 模型路由穿过真实 Kilo 免费模型，"
                + "驱动“问题→决策→真实工具调用→证据回收→综合→持久化引用”。检索后端为确定性关键词解析。"
                + "只有结构化识别的外部供应商故障才记为 BLOCKED_EXTERNAL 并跳过；内部失败为测试失败。"
                + "每场景模型调用轮数和总墙钟均受限。");
        document.put("provider", "KILO_GATEWAY_FREE");
        document.put("model", MODEL);
        document.put("scenarios", SCENARIOS);
        Files.createDirectories(EVIDENCE_PATH.getParent());
        Files.writeString(EVIDENCE_PATH,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(document), StandardCharsets.UTF_8);
        System.out.println("REAL_AGENT_LOOP_EVIDENCE=" + EVIDENCE_PATH.toAbsolutePath());
    }
}
