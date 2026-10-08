package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.application.view.AgentMessageView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPlan;
import com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ZenModelExecution;
import com.shitulelv.aicollab.infrastructure.ai.model.AiConfigurationContext;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上下文可靠性 D1-D8 行为回归（2026-10-08 扩展复核 + 后续复核探针的正式化）。
 *
 * <p>使用真实生产组件：真实 Composer / Summarizer / Coordinator / Routing 执行器 /
 * Legacy 执行器 / MemoryService；仅 Repository、native 出站、ChatModelGateway、
 * 模型配置存储与记忆仓库为受控替身。不反射私有方法、不建立同名生产替身、不调用真实模型。
 * D2 的并发/租约 fencing 在 {@code AgentRunContextCommitPostgresTest}（真实 PostgreSQL + 真实事务）。</p>
 *
 * <p>用例来源（未修复基线 2cf4c51 上红灯实测后修复转绿）：</p>
 * <ul>
 *   <li>D1 partialTailRemainsInActualMainRequest（探针 C1C5FollowupProbe）</li>
 *   <li>D3 lengthTerminated…DoesNotAdvance/Commit（探针 C1C5ExpandedProbe，两个 scope）</li>
 *   <li>D4 childV2DoesNotInheritProjectMemory + 主运行镜像（探针 C1C5ExpandedProbe）</li>
 *   <li>D5 nearWindowV2ConversationSummaryHasIndependentInput（探针 C1C5ExpandedProbe）</li>
 *   <li>D6 fullToolLayerStillCountsUnvisitedOlderSources（探针 C1C5ExpandedProbe）</li>
 *   <li>D7 legacyOutboundKeepsCommittedRunSummaryAndGuidance（探针 C1C5ExpandedProbe）</li>
 *   <li>D8 failedAuxiliaryStillRefreshesNextMainRequest（探针 C1C5ExpandedProbe）</li>
 * </ul>
 */
class AgentContextReliabilityD1D8RegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private AgentRepository repository;
    private NativeToolCallingExecutor nativeExecutor;
    private RoutingAgentModelExecutor model;
    private AgentContextSummarizer summarizer;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(new ArrayList<>());
        when(repository.citationsStillValid(any(), any())).thenReturn(true);
        nativeExecutor = mock(NativeToolCallingExecutor.class);
        model = controlledRouting(nativeExecutor, null);
        summarizer = new AgentContextSummarizer(repository, model, JSON);
    }

    // ---------------------------------------------------------------- 受控边界

    private static UserAiProvider provider(String name) {
        var now = OffsetDateTime.now();
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), name,
                ModelProviderType.OPENAI_COMPATIBLE, "https://example.invalid", "/v1/chat/completions",
                "encrypted", name, true, 0.2, 1024,
                java.util.EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS),
                true, now, now, null);
    }

    private static RoutingAgentModelExecutor controlledRouting(
            NativeToolCallingExecutor nativeExecutor, AgentModelConfigurationStore store) {
        var actualStore = store != null ? store : mock(AgentModelConfigurationStore.class);
        if (store == null) when(actualStore.require(any())).thenReturn(provider("controlled"));
        var zen = mock(ZenModelExecution.class);
        when(zen.isZen(any())).thenReturn(false);
        return new RoutingAgentModelExecutor(nativeExecutor, mock(LegacyReadOnlyAgentExecutor.class), zen, actualStore);
    }

    private AgentRunView run(int depth) {
        var now = OffsetDateTime.now();
        return new AgentRunView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                depth > 0 ? UUID.randomUUID() : null,
                depth > 0 ? "KNOWLEDGE_RESEARCHER" : "SUPERVISOR", depth,
                "Review document findings", AgentRunStatus.RUNNING,
                64, 64, 3, null, null, 0, 0, 0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, "PROJECT_RESEARCH", 1, 2, false, now, now);
    }

    private AgentExecutionContext context(AgentRunView run) {
        return new AgentExecutionContext(run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                "OWNER", false, AgentPageContext.empty(), AgentResourcePolicy.v2Limits(run.depth()),
                run.depth(), List.of());
    }

    private AgentSkill skill(AgentRunView run) {
        AgentSkill skill = mock(AgentSkill.class);
        when(skill.code()).thenReturn(run.depth() > 0 ? "CHILD_DOCUMENT_RESEARCH" : "PROJECT_RESEARCH");
        when(skill.instruction()).thenReturn("Read-only document research.");
        when(skill.outputContract()).thenReturn("Evidence and gaps.");
        return skill;
    }

    /** 通用受控 Repository：协调器 advance 所需的最小桩（v2 运行、无待恢复轮次）。 */
    private AgentRepository coordinatorRepository(AgentRunView run) {
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(new ArrayList<>());
        when(repository.findRun(any(), any())).thenReturn(Optional.of(run));
        when(repository.citationsStillValid(any(), any())).thenReturn(true);
        when(repository.beginSummaryAttempt(any())).thenReturn(UUID.randomUUID());
        when(repository.beginRunContextAttempt(any(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(UUID.randomUUID());
        when(repository.beginModelCall(any(), any(), anyString())).thenReturn("probe-main-call");
        when(repository.recordModelTurnWithSettlement(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.recordFinal(any(), anyString(), anyList(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return repository;
    }

    private static void whenBooleanAny(com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext ignored) {
        // 占位：无操作
    }

    private AgentStepView toolStep(int seq, String tool, String text) {
        return new AgentStepView(UUID.randomUUID(), seq, AgentStepType.TOOL_CALL_COMPLETED, tool, null,
                JSON.createObjectNode().put("data", text), "TOOL_SUCCESS", null, null, false, null, null,
                OffsetDateTime.now());
    }

    private AgentStepView latestModelRequest(int sequence) {
        return new AgentStepView(UUID.randomUUID(), sequence, AgentStepType.MODEL_REQUEST,
                null, null, null, "latest", null, null, false, null, null, OffsetDateTime.now());
    }

    private ModelTurnResult turn(String content, ModelFinishReason reason) {
        return new ModelTurnResult(content, List.of(), reason, new ModelUsage(1000, 100),
                "controlled", "controlled", 1L);
    }

    private static boolean isSummary(List<ModelMessage> messages) {
        return !messages.isEmpty() && messages.getFirst().toString().contains("摘要器");
    }

    // ================================================================
    // D1：partial 首条记录的未送入尾部必须留在实际主请求中
    // ================================================================

    @Test
    void partialTailRemainsInActualMainRequestAfterCommittedPartialSummary() {
        AgentRunView run = run(0);
        var now = OffsetDateTime.now();
        var huge = new AgentStepView(UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "read_document_section", JSON.createObjectNode().put("toolCallId", "call-1"),
                JSON.createObjectNode().put("status", "SUCCEEDED").put("content", "x".repeat(80000) + "UNSENT_TAIL_4907"),
                "TOOL_SUCCESS", null, null, false, null, null, now);
        var latest = latestModelRequest(2);
        when(repository.listSteps(any(), any())).thenReturn(List.of(huge, latest));
        when(repository.beginRunContextAttempt(any(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(UUID.randomUUID());
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any()))
                .thenReturn(turn("Only the supplied prefix", ModelFinishReason.STOP));
        var budget = new AgentContextBudget.Budget(950000, 950000, 256000, 128000,
                AgentContextBudget.BINDING_MODEL_WINDOW, false);

        summarizer.compactRunContext(run, null, budget, Integer.MAX_VALUE, () -> true);

        // 摘要按 partial 偏移提交（兼容既有 partial 元数据契约）
        ArgumentCaptor<JsonNode> summary = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).completeRunContextAttempt(any(), eq("COMMITTED"), anyString(),
                any(), anyString(), summary.capture(), eq(0));
        assertThat(summary.getValue().path("sourceThroughSequence").asInt(0)).isEqualTo(1);
        assertThat(summary.getValue().path("sourcePartialSequence").asInt(0)).isEqualTo(1);
        assertThat(summary.getValue().path("sourcePartialChars").asInt(0)).isGreaterThan(0);

        // D1 核心：partial 提交后，未送入摘要的尾部仍进入实际组装消息；
        // 工具调用/result 成对（引用身份完整）。
        when(repository.latestRunContextSummary(any(), any())).thenReturn(summary.getValue());
        var composer = new AgentModelMessageComposer(repository, null, JSON);
        var composition = composer.composeV2(run, context(run), skill(run),
                AgentPlan.create(run.goal(), List.of()), List.of(huge, latest), 950000, 1.0, false);
        assertThat(composition.failureReason()).isNull();
        String request = composition.messages().toString();
        assertThat(request).as("partial 尾部必须保留在实际主请求中").contains("UNSENT_TAIL_4907");
        assertThat(request).as("工具调用/result 保持成对").contains("call-1");
    }

    // ================================================================
    // D3：finishReason=LENGTH 的截断摘要不能发布并推进覆盖（两个 scope）
    // ================================================================

    @Test
    void lengthTerminatedRunContextSummaryIsDowngradedWithoutAdvancingCoverage() {
        AgentRunView run = run(0);
        when(repository.listSteps(any(), any())).thenReturn(List.of(
                toolStep(1, "list_project_documents", "Evidence"), latestModelRequest(2)));
        when(repository.beginRunContextAttempt(any(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(UUID.randomUUID());
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any()))
                .thenReturn(turn("The generated summary ends before its findings are complete",
                        ModelFinishReason.LENGTH));
        var budget = new AgentContextBudget.Budget(50000, 50000, 42500, 21250,
                "PER_REQUEST_CAP", true);

        boolean committed = summarizer.compactRunContext(run, null, budget, Integer.MAX_VALUE, () -> true);

        assertThat(committed).isFalse();
        verify(repository, never()).completeRunContextAttempt(any(), eq("COMMITTED"),
                anyString(), any(), anyString(), any(), any());
    }

    @Test
    void lengthTerminatedConversationSummaryIsDowngradedWithoutCommitting() {
        AgentRunView run = run(0);
        // 与 AgentContextSummarizerTest 相同的最小 v2 工作状态与候选
        var state = JSON.createObjectNode().put("schemaVersion", 2).put("stateRevision", 5)
                .put("goalRevision", 1).put("activeGoal", run.goal()).put("latestRequest", run.goal());
        state.putArray("constraints");
        when(repository.workingState(any(), any())).thenReturn(state);
        when(repository.countSummaryAttempts(any(), any())).thenReturn(0);
        when(repository.beginSummaryAttempt(any())).thenReturn(UUID.randomUUID());
        var candidates = List.of(new AgentMessageView(UUID.randomUUID(), run.sessionId(), run.id(),
                "USER", "An earlier explicit decision must remain available", null, null,
                OffsetDateTime.now().minusDays(1)));
        var composition = new AgentModelMessageComposer.Composition(
                List.of(), AgentModelMessageComposer.CompositionStats.empty(), null, candidates);
        var executor = mock(RoutingAgentModelExecutor.class);
        when(executor.resolveRequest(any())).thenReturn(AgentRuntimeBehaviorTest.resolved(true));
        when(executor.callModelWithoutTools(any(), any(), any()))
                .thenReturn(new ModelTurnResult("A truncated summary draft", List.of(),
                        ModelFinishReason.LENGTH, null, "p", "m", 5L));
        var summarizer = new AgentContextSummarizer(repository, executor, JSON);

        summarizer.maybeSummarize(run, composition, 10_000);

        verify(repository).completeSummaryAttempt(any(), eq("DOWNGRADED_UNQUALIFIED"), anyString(), any(), any());
        verify(repository, never()).commitConversationSummary(any(), any(), anyInt(), anyInt(), any());
    }

    // ================================================================
    // D4：子运行（v2 与回退路径）不继承父项目记忆；主运行正常读取
    // ================================================================

    @Test
    void childV2DoesNotInheritProjectMemoryWhileParentKeepsIt() {
        var memoryRepository = mock(AgentMemoryRepository.class);
        var access = mock(ProjectAccessGuard.class);
        when(access.requireMember(any(), any())).thenReturn(ProjectRole.OWNER);
        var now = OffsetDateTime.now();
        var memory = new AgentMemoryView(UUID.randomUUID(), UUID.randomUUID(), "PREFERENCE",
                "Parent preference", "PARENT_MEMORY_SENTINEL_6279", "MANUAL", null, "ACTIVE",
                UUID.randomUUID(), UUID.randomUUID(), 1, now, now);
        when(memoryRepository.list(any(), eq(true), eq(100))).thenReturn(List.of(memory));
        var memories = new AgentMemoryService(memoryRepository, access);

        // 子运行（depth=1，真实非空记忆选择）：v2 与回退路径都不得注入父项目记忆
        var child = run(1);
        var childComposer = new AgentModelMessageComposer(repository, memories, JSON);
        var childComposition = childComposer.composeV2(child, context(child), skill(child),
                AgentPlan.create(child.goal(), List.of()), List.of(), 50000, 1.0, false);
        assertThat(childComposition.messages().toString())
                .as("depth=1 v2 请求不得包含父项目记忆")
                .doesNotContain("PARENT_MEMORY_SENTINEL_6279");
        assertThat(childComposition.stats().memoryIncluded()).isFalse();
        var childLegacy = childComposer.buildMessageHistory(child, context(child), skill(child),
                AgentPlan.create(child.goal(), List.of()), List.of(), false);
        assertThat(childLegacy.toString())
                .as("composer-v2=false 回退路径同样不得给子运行父项目记忆")
                .doesNotContain("PARENT_MEMORY_SENTINEL_6279");

        // 镜像：主运行（depth=0）正常使用非空项目记忆
        var parent = run(0);
        var parentComposition = childComposer.composeV2(parent, context(parent), skill(parent),
                AgentPlan.create(parent.goal(), List.of()), List.of(), 50000, 1.0, false);
        assertThat(parentComposition.messages().toString())
                .as("主运行必须保留正常的项目记忆注入（非空真实选择）")
                .contains("PARENT_MEMORY_SENTINEL_6279");
        assertThat(parentComposition.stats().memoryIncluded()).isTrue();
    }

    // ================================================================
    // D5：v2 会话摘要与主请求分别核对自己的单次窗口
    // ================================================================

    @Test
    void nearWindowV2ConversationSummaryHasIndependentInput() {
        var summaryCalls = new AtomicInteger();
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            List<ModelMessage> messages = invocation.getArgument(0);
            if (isSummary(messages)) summaryCalls.incrementAndGet();
            return turn("A complete answer.", ModelFinishReason.STOP);
        });
        var kernel = kernel(model);
        when(kernel.repository().listRecentMessages(any(), anyInt())).thenReturn(List.of(
                new AgentMessageView(UUID.randomUUID(), kernel.run().sessionId(), kernel.run().id(),
                        "ASSISTANT", "h".repeat(90000), null, null, OffsetDateTime.now().minusMinutes(1))));

        var outcome = kernel.coordinator().advance(kernel.run());

        assertThat(summaryCalls.get())
                .as("v2 主请求放得进回退窗口时，旧对话摘要必须按自己的单次窗口独立发送")
                .isPositive();
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    // ================================================================
    // D6：工具层恰好耗满时，未遍历到的旧来源仍进入裁前统计
    // ================================================================

    @Test
    void fullToolLayerStillCountsUnvisitedOlderSources() {
        AgentRunView run = run(0);
        var composer = new AgentModelMessageComposer(repository, null, JSON);
        var skill = skill(run);
        var plan = AgentPlan.create(run.goal(), List.of());
        var baseline = composer.composeV2(run, context(run), skill, plan, List.of(), 20000, 1.0, false);
        int mandatory = baseline.stats().charsUsed() - run.goal().length();
        int toolShare = Math.max(6000, (int) ((60000 - mandatory) * 0.45));
        int emptyOutputSize = JSON.createObjectNode().put("data", "").toString().length();
        var old = toolStep(1, "list_tasks", "o".repeat(180000));
        var newest = toolStep(2, "list_tasks", "n".repeat(toolShare - emptyOutputSize));

        var composition = composer.composeV2(run, context(run), skill, plan, List.of(old, newest), 20000, 1.0, false);

        assertThat(composition.stats().toolStepsCandidates()).isEqualTo(2);
        assertThat(composition.stats().toolStepsIncluded()).isEqualTo(1);
        assertThat(composition.stats().droppedSourceChars())
                .as("工具层耗满后未遍历的旧来源必须计入裁前统计（去重/无关来源除外）")
                .isGreaterThanOrEqualTo(old.output().toString().length());
    }

    // ================================================================
    // D7：Legacy 实际出站命令保留 Composer 组装的全部必要层
    // ================================================================

    @Test
    void legacyOutboundKeepsCommittedRunSummaryAndGuidance() {
        AgentRunView run = run(0);
        when(repository.workingState(any(), any())).thenReturn(JSON.createObjectNode()
                .put("schemaVersion", 2).put("activeGoal", run.goal()).put("latestRequest", run.goal()));
        when(repository.latestRunContextSummary(any(), any())).thenReturn(JSON.createObjectNode()
                .put("text", "COMMITTED_RUN_SUMMARY_SENTINEL_8163").put("sourceThroughSequence", 1));
        var composer = new AgentModelMessageComposer(repository, null, JSON);
        var messages = new ArrayList<>(composer.composeV2(run, context(run), skill(run),
                AgentPlan.create(run.goal(), List.of()), List.of(), 50000, 1.0, true).messages());
        messages.add(new ModelMessage.System("FINALIZING_GUIDANCE_SENTINEL_9551"));

        var gateway = mock(ChatModelGateway.class);
        when(gateway.complete(any(), any())).thenReturn(new ChatCompletionResult(
                "{\"action\":\"final\",\"answer\":\"Done.\",\"citations\":[],\"inferences\":[]}",
                "controlled", "legacy", 100, 10, 1L));
        var legacy = new LegacyReadOnlyAgentExecutor(gateway, new com.shitulelv.aicollab.agent.application.AgentDecisionParser(JSON), JSON);
        legacy.callModel(messages, List.of(), run.projectId(), run.requesterId(), false, run.sessionId());

        ArgumentCaptor<ChatCompletionCommand> command = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(gateway).complete(command.capture(), any());
        var outbound = command.getValue();
        // 检查实际出站命令，而不是只断言中间 messages 有标记
        assertThat(outbound.userPrompt())
                .as("Legacy 出站必须保留有效 RUN_CONTEXT 摘要层")
                .contains("COMMITTED_RUN_SUMMARY_SENTINEL_8163");
        assertThat(outbound.systemPrompt())
                .as("Legacy 出站必须保留收尾指令等后续 System 层")
                .contains("FINALIZING_GUIDANCE_SENTINEL_9551");
        assertThat(outbound.tools()).as("Legacy 保持只读协议（无工具定义）").isEmpty();
    }

    // ================================================================
    // D8：辅助请求失败后，下一次实际主请求按当前配置重新准备
    // ================================================================

    @Test
    void failedAuxiliaryStillRefreshesNextMainRequest() {
        var store = mock(AgentModelConfigurationStore.class);
        var a = provider("model-A");
        var b = provider("model-B");
        when(store.require(any())).thenReturn(a, b);
        var routing = controlledRouting(nativeExecutor, store);
        var calls = new ArrayList<String>();
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            List<ModelMessage> messages = invocation.getArgument(0);
            calls.add(AiConfigurationContext.current().modelName());
            if (isSummary(messages)) throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "Controlled auxiliary failure");
            return turn("A complete answer.", ModelFinishReason.STOP);
        });
        var kernel = kernel(routing);

        var outcome = kernel.coordinator().advance(kernel.run());

        assertThat(calls).as("夹具应产生一次辅助出站与一次主出站").hasSize(2);
        assertThat(calls.getLast())
                .as("辅助失败后下一次主请求必须重新解析当前配置（A→B），不能沿用旧快照")
                .isEqualTo("model-B");
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    // ---------------------------------------------------------------- D5/D8 共用内核

    private record Kernel(AgentRunView run, AgentRepository repository, AgentRuntimeCoordinator coordinator) {}

    private Kernel kernel(RoutingAgentModelExecutor modelExecutor) {
        AgentRunView run = run(0);
        AgentRepository repository = coordinatorRepository(run);
        var state = JSON.createObjectNode().put("schemaVersion", 2).put("stateRevision", 1)
                .put("goalRevision", 0).put("activeGoal", run.goal()).put("latestRequest", run.goal());
        state.putArray("constraints");
        when(repository.workingState(any(), any())).thenReturn(state);
        var candidates = List.of(new AgentMessageView(UUID.randomUUID(), run.sessionId(), run.id(), "USER",
                "An earlier explicit decision must remain available", null, null,
                OffsetDateTime.now().minusDays(1)));
        when(repository.listSummaryCandidates(any(), any(), anyInt())).thenReturn(candidates);
        var assembler = mock(AgentContextAssembler.class);
        when(assembler.assemble(any(), any(), any())).thenReturn(context(run));
        var skill = skill(run);
        var registry = mock(com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry.class);
        when(registry.select(any(), any(), any())).thenReturn(skill);
        var planService = mock(AgentPlanService.class);
        when(planService.ensurePlan(any(), any())).thenReturn(AgentPlan.create(run.goal(), List.of()));
        var composer = new AgentModelMessageComposer(repository, null, JSON);
        var summarizer = new AgentContextSummarizer(repository, modelExecutor, JSON);
        var coordinator = new AgentRuntimeCoordinator(repository, assembler, registry, planService,
                new AgentToolRegistry(List.of()), new AgentCancellationService(repository), modelExecutor,
                new AgentConvergencePolicy(), JSON, null, AgentContextProperties.defaults(), composer,
                mock(AgentToolCallExecutor.class), summarizer);
        return new Kernel(run, repository, coordinator);
    }
}
