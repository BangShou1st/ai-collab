package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.shitulelv.aicollab.agent.application.runtime.AgentModelMessageComposer;
import com.shitulelv.aicollab.agent.application.runtime.AgentPlanService;
import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.application.runtime.AgentToolCallExecutor;
import com.shitulelv.aicollab.agent.application.runtime.AgentToolScheduler;
import com.shitulelv.aicollab.agent.application.runtime.RoutingAgentModelExecutor;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.ClaimedAgentRun;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.document.application.service.DocumentApplicationService;
import com.shitulelv.aicollab.document.application.service.DocumentContentService;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.application.view.DocumentView;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-RAG-01C 阶段 C（P1）：Agent 复杂研究的<b>机械链路对照</b>——同一组真实文档与问题上，
 * 比较 A（主 Agent 直接用知识检索/目录/正文读取工具研究）与 B（委派 document_research
 * 只读子 Agent 再由父综合）的行为事实。
 *
 * <p><b>真实的部分</b>：真实 PostgreSQL（Flyway 迁移的真实 schema）、真实 Agent 持久执行引擎
 * （claim/advance/requeue/resumeParent）、真实工具实现（{@code search_project_knowledge}、
 * {@code list_project_documents}、{@code get_document_outline}、{@code read_document_section}、
 * {@code delegate_document_research}）、真实正文读取服务、真实来源身份（documentId/chunkId）。</p>
 *
 * <p><b>脚本化的部分</b>：Chat 模型轮次由 mock 注入确定性响应（{@code RoutingAgentModelExecutor}），
 * 检索候选由测试注入到真实 chunk 上。因此本类度量的是<b>委派链路与证据回收的机制正确性</b>，
 * <b>不是真实模型回答质量</b>。真实模型质量需要真实 Chat 供应商；本轮外部供应商限流时，
 * 该层如实标记为 {@code BLOCKED_EXTERNAL}（见交付文档），不用脚本冒充。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentResearchQualityPostgresTest {
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

    private DocumentContentService content;
    private DocumentApplicationService documents;

    private final Queue<ModelTurnResult> parentResponses = new ConcurrentLinkedQueue<>();
    private final Queue<ModelTurnResult> childResponses = new ConcurrentLinkedQueue<>();
    private final List<String> modelRequests = new ArrayList<>();
    private final List<List<String>> exposedToolNames = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger modelCalls = new java.util.concurrent.atomic.AtomicInteger();
    private Function<String, List<DocumentSearchHit>> searchResolver = query -> List.of();

    private UUID user;
    private UUID project;
    private UUID session;
    private final Map<String, UUID> documentIds = new LinkedHashMap<>();
    private final Map<String, UUID> chunkIds = new LinkedHashMap<>();

    private static final Map<String, Map<String, Object>> SCENARIOS = new LinkedHashMap<>();
    /** C1 同题对照结果（直接研究 vs 委派研究）。 */
    private static final Map<String, Map<String, Object>> PAIRED_COMPARISONS = new LinkedHashMap<>();
    static final Path EVIDENCE_PATH = Path.of("target", "retrieval-baseline", "2026-10-19",
            "agent-research-quality-paired.json");

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
        for (String table : List.of("agent_planning_operation_event", "agent_planning_operation",
                "agent_usage_settlement", "agent_tool_invocation", "agent_step", "agent_run_event",
                "agent_message", "agent_run", "agent_session", "document_body_chunk", "document_body",
                "document_chunk", "project_document", "project_member", "project", "app_user")) {
            jdbc.update("DELETE FROM " + table);
        }
        parentResponses.clear();
        childResponses.clear();
        modelRequests.clear();
        exposedToolNames.clear();
        modelCalls.set(0);
        searchResolver = query -> List.of();

        assembler = mock(AgentContextAssembler.class);
        events = mock(AgentEventService.class);
        approvals = mock(AgentApprovalService.class);
        modelExecutor = mock(RoutingAgentModelExecutor.class);
        ReflectionTestUtils.setField(recorder, "events", events);
        recovery = new AgentRecoveryJob(repository);

        when(assembler.assemble(any(), any(), any())).thenAnswer(invocation -> {
            AgentRunView run = invocation.getArgument(0);
            return run == null ? null : context(run);
        });
        when(modelExecutor.resolveRequest(any())).thenReturn(resolved(false));
        when(modelExecutor.callModel(any(), any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            modelCalls.incrementAndGet();
            AgentRunView run = invocation.getArgument(0);
            List<ModelMessage> messages = invocation.getArgument(1);
            modelRequests.add(messages == null ? "(none)" : messages.toString());
            List<com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition> exposed =
                    invocation.getArgument(2);
            exposedToolNames.add(exposed == null ? List.of()
                    : exposed.stream().map(com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition::name)
                            .toList());
            if (run != null && run.depth() > 0) {
                ModelTurnResult next = childResponses.poll();
                return next != null ? next : finalText("子研究未能取得有效证据（研究缺口）。");
            }
            ModelTurnResult next = parentResponses.poll();
            return next != null ? next : finalText("综合回答");
        });

        fixture();
        seedCorpus();
        coordinator = coordinator(registry());
    }

    // ============================================================ 场景 1：直接研究

    @Test
    void directResearchSynthesizesCrossDocumentEvidenceWithRealSources() {
        UUID verify = documentIds.get("上线验收规程.md");
        UUID rollback = documentIds.get("发布回滚预案.md");
        searchResolver = query -> List.of(
                hit(verify, 0, "上线门禁", "生产发布前必须完成核心业务流程的自动化验收，所有阻断级缺陷必须关闭。", 0.91),
                hit(rollback, 0, "回滚触发条件", "上线后核心指标连续两次低于阈值时立即暂停发布并回滚到最近稳定版本。", 0.88));

        parentResponses.add(toolCall("p1", "search_project_knowledge",
                json.createObjectNode().put("query", "上线验收需要满足哪些条件？指标不达标如何处理？")));
        parentResponses.add(finalText("上线验收需完成核心业务流程自动化验收并关闭阻断级缺陷；"
                + "指标连续两次低于阈值时应暂停发布并回滚到最近稳定版本。"));

        AgentRunView parent = newRun("结合文档说明上线验收与回滚条件");
        AgentWorkerOutcome outcome = drive(parent);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(toolSteps(parent, "search_project_knowledge"))
                .as("直接研究必须真实调用知识检索工具").isNotEmpty();
        String citations = finalCitations(parent);
        assertThat(citations).as("最终回答必须携带真实来源（非模型编造）")
                .contains(verify.toString()).contains(rollback.toString());
        record("A-direct-launch-rollback", outcome, parent, filterCitations(citations, 2));
    }

    // ============================================================ 场景 2：委派研究

    @Test
    void delegatedResearchReturnsChildFindingsAndSourcesToParent() {
        UUID verify = documentIds.get("上线验收规程.md");
        searchResolver = query -> List.of(
                hit(verify, 0, "上线门禁", "生产发布前必须完成核心业务流程的自动化验收，所有阻断级缺陷必须关闭。", 0.9));

        // 父：先委派，子完成后综合
        parentResponses.add(toolCall("p1", "delegate_document_research",
                json.createObjectNode().put("objective", "查证上线验收需要满足的条件并给出真实来源，读全相关章节")));
        parentResponses.add(finalText("依据子研究：上线验收要求完成核心业务流程自动化验收并关闭阻断级缺陷。"));
        // 子：检索 + 正文读取 + 结论
        childResponses.add(toolCall("c1", "search_project_knowledge",
                json.createObjectNode().put("query", "上线验收条件")));
        childResponses.add(toolCall("c2", "read_document_section",
                json.createObjectNode().put("documentId", verify.toString())));
        childResponses.add(finalText("研究发现：上线门禁要求自动化验收全部通过，阻断级缺陷必须关闭。"));

        AgentRunView parent = newRun("委派子 Agent 研究上线验收条件");
        AgentWorkerOutcome outcome = drive(parent);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        var children = repository.childRuns(project, parent.id());
        assertThat(children).as("委派必须创建一个 depth=1 的只读子运行").hasSize(1);
        assertThat(children.getFirst().depth()).isEqualTo(1);
        assertThat(children.getFirst().role()).isEqualTo("KNOWLEDGE_RESEARCHER");
        assertThat(steps(parent, "DELEGATION_REQUESTED")).isEqualTo(1);
        assertThat(steps(parent, "DELEGATION_COMPLETED")).isEqualTo(1);
        // 父综合请求里必须实际注入子运行产出（真实回收，而不是父自己重查）
        assertThat(modelRequests.stream().anyMatch(request -> request.contains("上线门禁要求自动化验收")))
                .as("父综合请求必须包含子运行研究发现").isTrue();
        // 子来源身份随回收进入父最终回答
        assertThat(finalCitations(parent)).contains(verify.toString());
        record("B-delegated-launch", outcome, parent, filterCitations(finalCitations(parent), 1));
    }

    // ============================================================ 场景 3：相同条款来源身份

    @Test
    void identicalClauseKeepsTwoDistinctSourceIdentities() {
        UUID contractA = documentIds.get("合同模板甲.md");
        UUID contractB = documentIds.get("合同模板乙.md");
        String clause = "重大变更须经项目负责人书面批准后方可实施。";
        searchResolver = query -> List.of(
                hit(contractA, 0, "重大变更审批", clause, 0.9),
                hit(contractB, 0, "重大变更审批", clause, 0.9));

        parentResponses.add(toolCall("p1", "search_project_knowledge",
                json.createObjectNode().put("query", "重大变更审批要求")));
        parentResponses.add(finalText("两份合同的重大变更审批要求一致：均须项目负责人书面批准。"));

        AgentRunView parent = newRun("对比两份合同的重大变更审批条款");
        AgentWorkerOutcome outcome = drive(parent);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        String citations = finalCitations(parent);
        // 相同正文出现在两个文档：两个 documentId 必须都在，来源不能混淆/合并
        assertThat(citations).contains(contractA.toString()).contains(contractB.toString());
        record("A-identical-clause", outcome, parent, filterCitations(citations, 2));
    }

    // ============================================================ 场景 4：指定文档范围

    @Test
    void documentScopedSearchDoesNotLeakOtherDocuments() {
        UUID exports = documentIds.get("批量导出规范.md");
        UUID archive = documentIds.get("数据归档通告.md");
        searchResolver = query -> List.of(
                hit(exports, 0, "文件保留", "已完成的导出文件在临时存储区保存二十四小时。", 0.9),
                hit(archive, 0, "归档周期", "业务记录每季度归档一次，副本保留五年。", 0.7));

        parentResponses.add(toolCall("p1", "search_project_knowledge",
                json.createObjectNode().put("query", "导出文件保留多久")
                        .set("documentIds", json.createArrayNode().add(exports.toString()))));
        parentResponses.add(finalText("导出文件在临时存储区保留二十四小时。"));

        AgentRunView parent = newRun("只在导出规范文档内查询文件保留时长");
        AgentWorkerOutcome outcome = drive(parent);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        String citations = finalCitations(parent);
        assertThat(citations).contains(exports.toString());
        assertThat(citations).as("指定文档范围内不得引用范围外文档").doesNotContain(archive.toString());
        record("A-document-scoped", outcome, parent, filterCitations(citations, 1));
    }

    // ============================================================ 场景 5：不存在的事实

    @Test
    void absentFactIsReportedAsInsufficientWithoutFabricatedSources() {
        searchResolver = query -> List.of();

        parentResponses.add(toolCall("p1", "search_project_knowledge",
                json.createObjectNode().put("query", "审计日志必须保留多少年？")));
        parentResponses.add(finalText("当前项目资料不足以回答审计日志的保留年限，未在已索引文档中找到依据。"));

        AgentRunView parent = newRun("审计日志保留年限");
        AgentWorkerOutcome outcome = drive(parent);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        String citations = finalCitations(parent);
        assertThat(citations).as("没有证据时不得携带任何来源（不得补造引用）")
                .matches(value -> value == null || value.equals("[]") || value.isEmpty());
        String answer = finalAnswer(parent);
        assertThat(answer).as("应如实说明资料不足，而不是编造年限")
                .contains("不足").doesNotContain("五年").doesNotContain("保留五年");
        record("A-absent-fact", outcome, parent, List.of());
    }

    // ============================================================ 场景 6：工具失败不得当成功

    @Test
    void failedReadToolIsNotTreatedAsSuccessfulEvidence() {
        searchResolver = query -> List.of();
        UUID missing = UUID.randomUUID();

        parentResponses.add(toolCall("p1", "read_document_section",
                json.createObjectNode().put("documentId", missing.toString())));
        parentResponses.add(finalText("无法读取指定文档，未能取得可引用的正文证据。"));

        AgentRunView parent = newRun("读取一份不存在的文档");
        AgentWorkerOutcome outcome = drive(parent);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        String stepStatus = jdbc.queryForObject("""
                SELECT output_json->>'status' FROM agent_step
                WHERE run_id=? AND type='TOOL_CALL_COMPLETED' AND reason='TOOL_ERROR'
                  AND tool_name='read_document_section'
                """, String.class, parent.id());
        assertThat(stepStatus).as("读取不存在的文档必须如实记为失败，不得伪造成成功")
                .isEqualTo("FAILED");
        assertThat(finalCitations(parent)).as("失败工具不得贡献来源")
                .matches(value -> value == null || value.equals("[]") || value.isEmpty());
        record("A-failed-tool", outcome, parent, List.of());
    }

    // ============================================================ C1：同题直接研究 vs 子 Agent 委派对照

    /** 一条期望事实：marker 是最终回答里应出现的依据文本；chunkKeys 是支撑该事实的来源分块。 */
    private record Fact(String marker, List<String> chunkKeys) {}

    /**
     * 一个同题对照场景：直接与委派两种模式使用<b>同一问题、同一批文档、同一组输入事实、同一总预算</b>。
     * 两种模式的差别只在“是否把检索/阅读等研究动作委派给只读子 Agent”。
     */
    private record PairedScenario(
            String name, String goal,
            Function<String, List<DocumentSearchHit>> search,
            List<Fact> facts,
            List<ModelTurnResult> directParent,
            List<ModelTurnResult> delegatedParent,
            List<ModelTurnResult> delegatedChild) {}

    /** 一次运行的对照指标：事实覆盖、来源支持、错误引用、无依据断言、调用次数、预算与耗时。 */
    private record PairedMetrics(
            String mode, String status, int factsTotal, int factsCovered, int unsupportedFacts,
            int citationsTotal, int citationsSupported, int wrongCitations, int coverageGaps,
            int modelCalls, int toolCalls, int childRuns, boolean collectionComplete,
            int stepsUsed, int inputTokens, int outputTokens, long elapsedMs) {}

    private static String chunkKey(String filename, int chunkNo) {
        return filename + "#" + chunkNo;
    }

    @Test
    void directResearchVersusDelegatedResearchOnTheSameQuestionAndBudget() {
        UUID verify = documentIds.get("上线验收规程.md");
        UUID rollback = documentIds.get("发布回滚预案.md");
        UUID contractA = documentIds.get("合同模板甲.md");
        UUID contractB = documentIds.get("合同模板乙.md");
        UUID exports = documentIds.get("批量导出规范.md");
        UUID archive = documentIds.get("数据归档通告.md");
        UUID missing = UUID.randomUUID();

        List<PairedScenario> scenarios = List.of(
                // S1 多份文档分别提供上线与回退条件（跨文档综合）
                new PairedScenario("S1-上线与回退综合", "结合文档说明上线验收与回滚触发条件",
                        q -> List.of(
                                hit(verify, 0, "上线门禁",
                                        "生产发布前必须完成核心业务流程的自动化验收，所有阻断级缺陷必须关闭。", 0.91),
                                hit(rollback, 0, "回滚触发条件",
                                        "上线后核心指标连续两次低于约定阈值时，立即暂停发布并回滚到最近一次稳定版本。", 0.88)),
                        List.of(new Fact("自动化验收", List.of(chunkKey("上线验收规程.md", 0))),
                                new Fact("连续两次低于约定阈值", List.of(chunkKey("发布回滚预案.md", 0)))),
                        List.of(toolCall("s1-search", "search_project_knowledge",
                                        json.createObjectNode().put("query", "上线验收与回滚条件")),
                                finalText("上线验收要求完成自动化验收并关闭阻断级缺陷；"
                                        + "当核心指标连续两次低于约定阈值时应暂停发布并回滚到最近一次稳定版本。")),
                        List.of(toolCall("s1-delegate", "delegate_document_research",
                                        json.createObjectNode().put("objective", "查证上线验收与回滚触发条件并给出真实来源")),
                                finalText("依据子研究：上线验收要求完成自动化验收并关闭阻断级缺陷；"
                                        + "指标连续两次低于约定阈值时应暂停发布并回滚到最近一次稳定版本。")),
                        List.of(toolCall("s1-child-search", "search_project_knowledge",
                                        json.createObjectNode().put("query", "上线验收与回滚条件")),
                                finalText("研究发现：必须完成自动化验收并关闭阻断级缺陷；"
                                        + "核心指标连续两次低于约定阈值时应暂停发布并回滚。"))),
                // S2 两份合同中同一条款的来源身份（相同正文不得合并）
                new PairedScenario("S2-相同条款来源身份", "对比两份合同模板里重大变更审批条款是否一致",
                        q -> List.of(
                                hit(contractA, 0, "重大变更审批", "重大变更须经项目负责人书面批准后方可实施。", 0.9),
                                hit(contractB, 0, "重大变更审批", "重大变更须经项目负责人书面批准后方可实施。", 0.9)),
                        List.of(new Fact("项目负责人书面批准",
                                List.of(chunkKey("合同模板甲.md", 0), chunkKey("合同模板乙.md", 0)))),
                        List.of(toolCall("s2-search", "search_project_knowledge",
                                        json.createObjectNode().put("query", "重大变更审批要求")),
                                finalText("两份合同的重大变更审批条款完全一致：均须经项目负责人书面批准后方可实施。")),
                        List.of(toolCall("s2-delegate", "delegate_document_research",
                                        json.createObjectNode().put("objective", "比对两份合同模板的重大变更审批条款并给出真实来源")),
                                finalText("依据子研究：两份合同模板关于重大变更的审批要求完全一致，均须经项目负责人书面批准。")),
                        List.of(toolCall("s2-child-search", "search_project_knowledge",
                                        json.createObjectNode().put("query", "重大变更审批要求")),
                                finalText("研究发现：合同模板甲与合同模板乙的条款一致，均须经项目负责人书面批准后方可实施。"))),
                // S3 指定文档范围（问题点名《批量导出规范》）
                new PairedScenario("S3-指定文档范围", "根据《批量导出规范》说明导出文件保留多久",
                        q -> List.of(
                                hit(exports, 0, "文件保留",
                                        "已完成的导出文件在临时存储区保存二十四小时，过期后自动清理。", 0.9),
                                hit(archive, 0, "归档周期", "业务记录每季度归档一次，归档副本整体保留五年。", 0.7)),
                        List.of(new Fact("二十四小时", List.of(chunkKey("批量导出规范.md", 0)))),
                        List.of(toolCall("s3-search", "search_project_knowledge",
                                        json.createObjectNode().put("query", "导出文件保留多久")
                                                .set("documentIds", json.createArrayNode().add(exports.toString()))),
                                finalText("导出文件在临时存储区保留二十四小时，过期后自动清理。")),
                        List.of(toolCall("s3-delegate", "delegate_document_research",
                                        json.createObjectNode().put("objective", "在《批量导出规范》内查证导出文件保留时长并给出真实来源")),
                                finalText("依据子研究：导出文件在临时存储区保留二十四小时，过期后自动清理。")),
                        List.of(toolCall("s3-child-search", "search_project_knowledge",
                                        json.createObjectNode().put("query", "导出文件保留多久")
                                                .set("documentIds", json.createArrayNode().add(exports.toString()))),
                                finalText("研究发现：导出文件在临时存储区保存二十四小时，过期后自动清理。"))),
                // S4 资料不足时拒绝编造
                new PairedScenario("S4-资料不足拒绝编造", "审计日志必须保留多少年",
                        q -> List.of(),
                        List.of(),
                        List.of(toolCall("s4-search", "search_project_knowledge",
                                        json.createObjectNode().put("query", "审计日志必须保留多少年")),
                                finalText("当前项目资料不足以回答审计日志的保留年限，未在已索引文档中找到依据。")),
                        List.of(toolCall("s4-delegate", "delegate_document_research",
                                        json.createObjectNode().put("objective", "查证审计日志保留年限并如实报告是否资料不足")),
                                finalText("依据子研究：资料不足，无法确定审计日志保留年限，未编造年限。")),
                        List.of(toolCall("s4-child-search", "search_project_knowledge",
                                        json.createObjectNode().put("query", "审计日志必须保留多少年")),
                                finalText("研究发现：未找到相关依据，资料不足。"))),
                // S5 工具失败时的诚实降级
                new PairedScenario("S5-工具失败诚实降级", "读取一份不存在的文档并总结其内容",
                        q -> List.of(),
                        List.of(),
                        List.of(toolCall("s5-read", "read_document_section",
                                        json.createObjectNode().put("documentId", missing.toString())),
                                finalText("无法读取该文档，未取得可引用的正文证据。")),
                        List.of(toolCall("s5-delegate", "delegate_document_research",
                                        json.createObjectNode().put("objective", "尝试读取指定文档并如实报告读取失败")),
                                finalText("依据子研究：无法读取文档，未取得可引用证据。")),
                        List.of(toolCall("s5-child-read", "read_document_section",
                                        json.createObjectNode().put("documentId", missing.toString())),
                                finalText("研究发现：读取失败，无可用证据。"))));

        for (PairedScenario scenario : scenarios) {
            PairedMetrics direct = runMode(scenario, false);
            PairedMetrics delegated = runMode(scenario, true);
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("scenario", scenario.name());
            comparison.put("question", scenario.goal());
            comparison.put("direct", describeMetrics(direct));
            comparison.put("delegated", describeMetrics(delegated));
            comparison.put("deltaDelegatedMinusDirect", deltaMetrics(direct, delegated));
            PAIRED_COMPARISONS.put(scenario.name(), comparison);

            // 公平对照的硬性事实：同一问题、同一批文档、同一组输入事实。
            assertThat(direct.status()).as("%s：直接研究必须成功", scenario.name())
                    .isEqualTo("SUCCEEDED");
            assertThat(delegated.status()).as("%s：委派研究必须成功", scenario.name())
                    .isEqualTo("SUCCEEDED");
            assertThat(direct.factsCovered())
                    .as("%s：两种模式必须覆盖同一组输入事实", scenario.name())
                    .isEqualTo(delegated.factsCovered());
            assertThat(direct.unsupportedFacts()).as("%s：直接研究不得有无依据断言", scenario.name()).isZero();
            assertThat(delegated.unsupportedFacts()).as("%s：委派研究不得有无依据断言", scenario.name()).isZero();
            assertThat(delegated.collectionComplete()).as("%s：父子结果回收必须完整", scenario.name()).isTrue();
            assertThat(delegated.childRuns()).as("%s：委派必须产生恰好一个只读子运行", scenario.name()).isEqualTo(1);
            // 资料不足/工具失败场景：两种模式都不得补造来源。
            if (scenario.facts().isEmpty()) {
                assertThat(direct.citationsTotal()).as("%s：无证据时不得携带来源", scenario.name()).isZero();
                assertThat(delegated.citationsTotal()).as("%s：无证据时不得携带来源", scenario.name()).isZero();
            }
            // 指定文档范围场景：不得引用范围外文档。
            if (scenario.name().startsWith("S3-")) {
                assertThat(direct.wrongCitations()).as("指定范围不得越界引用").isZero();
                assertThat(delegated.wrongCitations()).as("指定范围不得越界引用").isZero();
            }
        }
    }

    private PairedMetrics runMode(PairedScenario scenario, boolean delegated) {
        parentResponses.clear();
        childResponses.clear();
        parentResponses.addAll(delegated ? scenario.delegatedParent() : scenario.directParent());
        childResponses.addAll(delegated ? scenario.delegatedChild() : List.of());
        searchResolver = scenario.search();
        int callsBefore = modelCalls.get();
        long started = System.nanoTime();
        AgentRunView run = pairedRun(scenario.goal());
        AgentWorkerOutcome outcome = drive(run);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        return metrics(scenario, delegated, run, outcome, modelCalls.get() - callsBefore, elapsedMs);
    }

    private AgentRunView pairedRun(String goal) {
        AgentRunView run = repository.createRun(project, session, user, goal, false, null, null);
        // 两种模式共用同一总预算限制，保证对照公平。
        jdbc.update("UPDATE agent_run SET max_steps=12, max_tool_calls=8, max_children=2 WHERE id=?",
                run.id());
        repository.claimNext("w-paired", OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        return repository.findRun(project, run.id()).orElseThrow();
    }

    private PairedMetrics metrics(PairedScenario scenario, boolean delegated, AgentRunView run,
                                  AgentWorkerOutcome outcome, int modelCallsUsed, long elapsedMs) {
        String rawAnswer = finalAnswer(run);
        String answer = rawAnswer == null ? "" : rawAnswer;
        // 引用载荷同时包含 documentId 与 chunkId：只把“真实来源分块（chunkId）”计为来源引用。
        java.util.Set<UUID> allChunks = new java.util.LinkedHashSet<>(chunkIds.values());
        java.util.Set<UUID> citations = new java.util.LinkedHashSet<>();
        String rawCitations = finalCitations(run);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                .matcher(rawCitations == null ? "[]" : rawCitations);
        while (matcher.find()) {
            UUID cited = UUID.fromString(matcher.group());
            if (allChunks.contains(cited)) citations.add(cited);
        }

        java.util.Set<UUID> allowed = new java.util.LinkedHashSet<>();
        for (Fact fact : scenario.facts()) {
            for (String key : fact.chunkKeys()) {
                UUID id = chunkIds.get(key);
                if (id != null) allowed.add(id);
            }
        }
        int covered = 0;
        int unsupported = 0;
        for (Fact fact : scenario.facts()) {
            if (!answer.contains(fact.marker())) continue;
            covered++;
            boolean backed = fact.chunkKeys().stream().map(chunkIds::get).anyMatch(citations::contains);
            if (!backed) unsupported++;
        }
        int supported = 0;
        int wrong = 0;
        for (UUID citation : citations) {
            if (allowed.contains(citation)) supported++;
            else wrong++;
        }
        var view = repository.findRun(project, run.id()).orElseThrow();
        int childRuns = repository.childRuns(project, run.id()).size();
        boolean collectionComplete = !delegated
                || (childRuns == 1 && steps(run, "DELEGATION_COMPLETED") == 1);
        int gaps = delegated ? coverageGapCount(run) : 0;
        return new PairedMetrics(delegated ? "DELEGATED" : "DIRECT",
                outcome == null ? "NULL" : outcome.status().name(),
                scenario.facts().size(), covered, unsupported,
                citations.size(), supported, wrong, gaps,
                modelCallsUsed, view.toolCallsUsed(), childRuns, collectionComplete,
                view.stepsUsed(), view.inputTokensUsed(), view.outputTokensUsed(), elapsedMs);
    }

    private int coverageGapCount(AgentRunView run) {
        List<Integer> rows = jdbc.queryForList("""
                SELECT jsonb_array_length(output_json->'coverage'->'gaps') FROM agent_step
                WHERE run_id=? AND type='DELEGATION_COMPLETED'
                """, Integer.class, run.id());
        return rows.isEmpty() || rows.getFirst() == null ? -1 : rows.getFirst();
    }

    private static Map<String, Object> describeMetrics(PairedMetrics metrics) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("mode", metrics.mode());
        item.put("status", metrics.status());
        item.put("factsTotal", metrics.factsTotal());
        item.put("factsCovered", metrics.factsCovered());
        item.put("unsupportedFacts", metrics.unsupportedFacts());
        item.put("citationsTotal", metrics.citationsTotal());
        item.put("citationsSupported", metrics.citationsSupported());
        item.put("wrongCitations", metrics.wrongCitations());
        item.put("coverageGaps", metrics.coverageGaps());
        item.put("modelCalls", metrics.modelCalls());
        item.put("toolCalls", metrics.toolCalls());
        item.put("childRuns", metrics.childRuns());
        item.put("childResultCollectionComplete", metrics.collectionComplete());
        item.put("stepsUsed", metrics.stepsUsed());
        item.put("inputTokensUsed", metrics.inputTokens());
        item.put("outputTokensUsed", metrics.outputTokens());
        item.put("elapsedMs", metrics.elapsedMs());
        return item;
    }

    private static Map<String, Object> deltaMetrics(PairedMetrics direct, PairedMetrics delegated) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("factsCovered", delegated.factsCovered() - direct.factsCovered());
        delta.put("unsupportedFacts", delegated.unsupportedFacts() - direct.unsupportedFacts());
        delta.put("citationsTotal", delegated.citationsTotal() - direct.citationsTotal());
        delta.put("wrongCitations", delegated.wrongCitations() - direct.wrongCitations());
        delta.put("modelCalls", delegated.modelCalls() - direct.modelCalls());
        delta.put("toolCalls", delegated.toolCalls() - direct.toolCalls());
        delta.put("stepsUsed", delegated.stepsUsed() - direct.stepsUsed());
        delta.put("inputTokensUsed", delegated.inputTokens() - direct.inputTokens());
        delta.put("outputTokensUsed", delegated.outputTokens() - direct.outputTokens());
        delta.put("elapsedMs", delegated.elapsedMs() - direct.elapsedMs());
        return delta;
    }

    // ============================================================ 驱动与装配

    private AgentWorkerOutcome drive(AgentRunView parent) {
        // 父运行已由 newRun 领取进入 RUNNING：先按真实执行引擎的语义直接推进，
        // 之后每次重新排队都通过 claimNext 重新领取（与生产 worker 的推进方式一致）。
        AgentWorkerOutcome last = coordinator.advance(repository.findRun(project, parent.id()).orElseThrow());
        for (int round = 0; round < 24; round++) {
            if (allTerminal(parent)) break;
            ClaimedAgentRun claimed = recovery.claim("w-drive-" + round, Duration.ofMinutes(6)).orElse(null);
            if (claimed == null) break;
            AgentRunView run = repository.findRun(project, claimed.id()).orElseThrow();
            last = coordinator.advance(run);
        }
        return last;
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
        repository.claimNext("w-create", OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
        return repository.findRun(project, run.id()).orElseThrow();
    }

    private AgentToolRegistryForTest registry() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        lenient().when(access.requireMember(any(), any())).thenReturn(ProjectRole.OWNER);
        DocumentSearchService search = mock(DocumentSearchService.class);
        lenient().when(search.search(any(), anyString(), any(), anyInt())).thenAnswer(invocation -> {
            String query = invocation.getArgument(1);
            List<UUID> scope = invocation.getArgument(2);
            List<DocumentSearchHit> hits = searchResolver.apply(query);
            if (scope == null || scope.isEmpty()) return hits;
            return hits.stream().filter(item -> scope.contains(item.documentId())).toList();
        });
        var knowledge = new KnowledgeSearchAgentTool(access, search, new KnowledgeContextBuilder(), json);
        var docs = new DocumentAgentTools();
        AgentTool list = docs.listProjectDocuments(this.documents, content, json);
        AgentTool outline = docs.getDocumentOutline(content);
        AgentTool read = docs.readDocumentSection(content);
        var delegate = new DocumentResearchDelegateAgentTool(json);
        return new AgentToolRegistryForTest(List.of(list, outline, read, knowledge, delegate));
    }

    /** 薄包装：只是给 AgentToolRegistry 一个明确的测试构造点。 */
    private static final class AgentToolRegistryForTest
            extends com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry {
        AgentToolRegistryForTest(List<AgentTool> tools) {
            super(tools);
        }
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
                run.depth() > 0 ? AgentRuntimeLimits.defaults()
                        : AgentRuntimeLimits.forSkill(run.skillCode()),
                run.depth(), List.of());
    }

    private static RoutingAgentModelExecutor.ResolvedRequest resolved(boolean nativeTools) {
        EnumSet<ModelCapability> capabilities = nativeTools
                ? EnumSet.of(ModelCapability.NATIVE_TOOLS, ModelCapability.CHAT)
                : EnumSet.of(ModelCapability.CHAT);
        OffsetDateTime now = OffsetDateTime.now();
        String modelName = nativeTools ? "test-native" : "test-legacy";
        var provider = new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), modelName,
                ModelProviderType.OPENAI_COMPATIBLE, "https://example.invalid", "/v1/chat/completions",
                "encrypted", modelName, true, 0.2, 1024, capabilities, true, now, now, null);
        return RoutingAgentModelExecutor.ResolvedRequest.of(provider, provider.toModelConfiguration());
    }

    // ============================================================ 夹具与种子

    private void fixture() {
        user = UUID.randomUUID();
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "res-" + user.toString().substring(0, 8), "test-only-hash", "ResearchQuality");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "Agent 研究质量", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        session = repository.createSession(project, user, "Agent 研究质量").id();
    }

    /**
     * 真实文档语料：每份文档都有 project_document（READY）、document_body（可读快照）、
     * document_body_chunk（正文），检索命中引用这些真实 chunkId，因此引用身份是真身份。
     */
    private void seedCorpus() {
        seedDocument("上线验收规程.md", List.of(
                chunk("上线门禁", "生产发布前必须完成核心业务流程的自动化验收，所有阻断级缺陷必须关闭。"),
                chunk("验收记录", "验收记录必须包含执行人、执行时间、测试版本与未关闭问题清单。")));
        seedDocument("发布回滚预案.md", List.of(
                chunk("回滚触发条件", "上线后核心指标连续两次低于约定阈值时，立即暂停发布并回滚到最近一次稳定版本。")));
        seedDocument("合同模板甲.md", List.of(
                chunk("重大变更审批", "重大变更须经项目负责人书面批准后方可实施。")));
        seedDocument("合同模板乙.md", List.of(
                chunk("重大变更审批", "重大变更须经项目负责人书面批准后方可实施。")));
        seedDocument("批量导出规范.md", List.of(
                chunk("文件保留", "已完成的导出文件在临时存储区保存二十四小时，过期后自动清理。")));
        seedDocument("数据归档通告.md", List.of(
                chunk("归档周期", "业务记录每季度归档一次，归档副本整体保留五年。")));

        var views = new ArrayList<DocumentView>();
        OffsetDateTime now = OffsetDateTime.now();
        for (Map.Entry<String, UUID> entry : documentIds.entrySet()) {
            views.add(new DocumentView(entry.getValue(), project, entry.getKey(), entry.getKey(),
                    "text/markdown", 100, DocumentStatus.READY, "markdown", 1, "OLLAMA", "test",
                    1024, null, user, "ResearchQuality", now, 1, now, now));
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

    private DocumentSearchHit hit(UUID documentId, int chunkNo, String heading, String content,
                                  double similarity) {
        UUID chunkId = chunkId(documentId, chunkNo);
        return new DocumentSearchHit(chunkId, documentId, filenameOf(documentId), heading, content,
                "hash-" + chunkId, similarity, Map.of("pageNumber", chunkNo + 1));
    }

    private UUID chunkId(UUID documentId, int chunkNo) {
        String filename = filenameOf(documentId);
        return chunkIds.get(filename + "#" + chunkNo);
    }

    private String filenameOf(UUID documentId) {
        return documentIds.entrySet().stream()
                .filter(entry -> entry.getValue().equals(documentId))
                .map(Map.Entry::getKey).findFirst().orElse("unknown.md");
    }

    // ============================================================ 模型脚本与断言辅助

    private static ModelTurnResult toolCall(String callId, String name, ObjectNode arguments) {
        return new ModelTurnResult("执行工具", List.of(new ModelToolCall(callId, name, arguments)),
                ModelFinishReason.TOOL_CALLS, new ModelUsage(500, 40),
                "OPENAI_COMPATIBLE", "test-legacy", 100L);
    }

    private static ModelTurnResult finalText(String content) {
        return new ModelTurnResult(content, List.of(), ModelFinishReason.STOP,
                new ModelUsage(600, 120), "OPENAI_COMPATIBLE", "test-legacy", 100L);
    }

    private int steps(AgentRunView run, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=? AND type=?",
                Integer.class, run.id(), type);
    }

    private List<String> toolSteps(AgentRunView run, String toolName) {
        return jdbc.queryForList("SELECT COALESCE(output_json->>'status','?') FROM agent_step "
                + "WHERE run_id=? AND type IN ('TOOL_CALL_COMPLETED','TOOL_CALL_FAILED') AND tool_name=?",
                String.class, run.id(), toolName);
    }

    private String finalCitations(AgentRunView run) {
        return jdbc.queryForObject("""
                SELECT COALESCE(citations_json::text,'[]') FROM agent_message
                WHERE run_id=? AND role='ASSISTANT' ORDER BY created_at DESC LIMIT 1
                """, String.class, run.id());
    }

    private String finalAnswer(AgentRunView run) {
        return jdbc.queryForObject("""
                SELECT content FROM agent_message WHERE run_id=? AND role='ASSISTANT'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, run.id());
    }

    private static List<String> filterCitations(String citations, int expected) {
        List<String> found = new ArrayList<>();
        if (citations == null) return found;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(citations);
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        while (matcher.find()) unique.add(matcher.group());
        found.addAll(unique);
        return found.size() <= expected ? found : found.subList(0, expected);
    }

    private void record(String name, AgentWorkerOutcome outcome, AgentRunView parent,
                        List<String> citations) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("scenario", name);
        item.put("mode", name.startsWith("B-") ? "DELEGATED" : "DIRECT");
        item.put("status", outcome == null ? "NULL" : outcome.status().name());
        item.put("childRunCount", repository.childRuns(project, parent.id()).size());
        item.put("delegationRequested", steps(parent, "DELEGATION_REQUESTED"));
        item.put("delegationCompleted", steps(parent, "DELEGATION_COMPLETED"));
        item.put("citationCount", citations.size());
        item.put("toolCallsUsed", repository.findRun(project, parent.id())
                .map(AgentRunView::toolCallsUsed).orElse(0));
        item.put("stepsUsed", repository.findRun(project, parent.id())
                .map(AgentRunView::stepsUsed).orElse(0));
        item.put("maxExposedTools", exposedToolNames.stream().mapToInt(List::size).max().orElse(0));
        SCENARIOS.put(name, item);
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("note", "D-RAG-01C 阶段 C：Agent 复杂研究的机械链路对照（真实 PostgreSQL + 真实工具 + "
                + "脚本化模型轮次）。度量委派受理/回收/来源身份保真与失败如实降级，不代表真实模型回答质量。"
                + "真实 Chat 供应商限流时该层为 BLOCKED_EXTERNAL。");
        document.put("scenarios", SCENARIOS);
        document.put("pairedDirectVsDelegated", PAIRED_COMPARISONS);
        Files.createDirectories(EVIDENCE_PATH.getParent());
        Files.writeString(EVIDENCE_PATH,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(document), StandardCharsets.UTF_8);
        System.out.println("AGENT_RESEARCH_QUALITY_EVIDENCE=" + EVIDENCE_PATH.toAbsolutePath());
    }
}
