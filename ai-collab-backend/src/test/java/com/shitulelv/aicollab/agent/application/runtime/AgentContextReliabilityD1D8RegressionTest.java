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
import java.util.Map;
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
        return provider(name, java.util.EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS));
    }

    private static UserAiProvider provider(String name, java.util.EnumSet<ModelCapability> capabilities) {
        var now = OffsetDateTime.now();
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), name,
                ModelProviderType.OPENAI_COMPATIBLE, "https://example.invalid", "/v1/chat/completions",
                "encrypted", name, true, 0.2, 1024,
                capabilities,
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

    // ================================================================
    // E2：RUN_CONTEXT 失败/不合格的辅助出站事实必须跨两个 scope 保留
    // ================================================================

    /**
     * RUN_CONTEXT 实际失败后，即使会话摘要分支因"无候选"跳过，辅助出站事实也不得被抹掉：
     * 下一次实际主请求必须按当前配置重新准备（实际出站模型断言，不只看中间 flag）。
     */
    @Test
    void failedRunContextCompactionStillRefreshesNextMainRequest() {
        var store = mock(AgentModelConfigurationStore.class);
        when(store.require(any())).thenReturn(provider("model-A"), provider("model-B"));
        var calls = new ArrayList<String>();
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            List<ModelMessage> messages = invocation.getArgument(0);
            calls.add(AiConfigurationContext.current().modelName());
            if (isAuxiliaryRequest(messages)) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "Controlled auxiliary failure");
            }
            return turn("A complete answer.", ModelFinishReason.STOP);
        });
        var routing = controlledRouting(nativeExecutor, store);
        AgentRunView run = run(0);
        var repository = coordinatorRepository(run);
        // 旧工具轨迹使裁前估算达到触发线；会话侧无任何候选
        var huge = toolStep(1, "read_document_section", "x".repeat(180_000));
        when(repository.listSteps(any(), any())).thenReturn(List.of(huge, latestModelRequest(2)));
        when(repository.listSummaryCandidates(any(), any(), anyInt())).thenReturn(List.of());
        var coordinator = coordinator(repository, routing, run,
                new AgentContextProperties(true, 50_000, 8_000, 2_000, Map.of()));

        var outcome = coordinator.advance(run);

        assertThat(calls).as("一次失败的 RUN_CONTEXT 出站后必须仍有一次主请求").hasSize(2);
        assertThat(calls.getFirst()).as("压缩按自己的配置快照使用当前模型 B").isEqualTo("model-B");
        assertThat(calls.getLast())
                .as("RUN_CONTEXT 失败后主请求必须按当前配置重新准备，不能被无会话候选的跳过抹掉")
                .isEqualTo("model-B");
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    /** RUN_CONTEXT 不合格（LENGTH 截断）同样保留辅助尝试事实，且绝不发布覆盖。 */
    @Test
    void unqualifiedRunContextCompactionStillRefreshesNextMainRequest() {
        var store = mock(AgentModelConfigurationStore.class);
        when(store.require(any())).thenReturn(provider("model-A"), provider("model-B"));
        var calls = new ArrayList<String>();
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            List<ModelMessage> messages = invocation.getArgument(0);
            calls.add(AiConfigurationContext.current().modelName());
            if (isAuxiliaryRequest(messages)) {
                return turn("truncated run-context draft", ModelFinishReason.LENGTH);
            }
            return turn("A complete answer.", ModelFinishReason.STOP);
        });
        var routing = controlledRouting(nativeExecutor, store);
        AgentRunView run = run(0);
        var repository = coordinatorRepository(run);
        when(repository.listSteps(any(), any())).thenReturn(List.of(
                toolStep(1, "read_document_section", "x".repeat(180_000)), latestModelRequest(2)));
        when(repository.listSummaryCandidates(any(), any(), anyInt())).thenReturn(List.of());
        var coordinator = coordinator(repository, routing, run,
                new AgentContextProperties(true, 50_000, 8_000, 2_000, Map.of()));

        var outcome = coordinator.advance(run);

        assertThat(calls).hasSize(2);
        assertThat(calls.getLast()).as("不合格不等于未尝试：主请求仍按当前配置重新准备")
                .isEqualTo("model-B");
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        verify(repository, never()).completeRunContextAttempt(any(), eq("COMMITTED"),
                anyString(), any(), anyString(), any(), anyInt());
        verify(repository).completeRunContextAttempt(any(), eq("DOWNSGRADED_UNQUALIFIED"),
                anyString(), any(), anyString(), any(), anyInt());
    }

    /**
     * 完全没有发生辅助出站（无任何辅助请求）时保持"每请求一次解析"：
     * 不得因为"估算很大"就误报已尝试，从而多做一次配置解析。
     */
    @Test
    void noAuxiliaryOutboundKeepsSingleConfigurationResolution() {
        var store = mock(AgentModelConfigurationStore.class);
        var resolves = new AtomicInteger();
        when(store.require(any())).thenAnswer(invocation -> {
            resolves.incrementAndGet();
            return provider("model-A");
        });
        var calls = new ArrayList<String>();
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            calls.add(AiConfigurationContext.current().modelName());
            return turn("A complete answer.", ModelFinishReason.STOP);
        });
        var routing = controlledRouting(nativeExecutor, store);
        AgentRunView run = run(0);
        var repository = coordinatorRepository(run);
        // 裁前估算远超触发线，但没有任何可压缩来源 → 压缩准入前即跳过，无出站
        var state = JSON.createObjectNode().put("schemaVersion", 2).put("stateRevision", 1)
                .put("goalRevision", 0).put("activeGoal", run.goal()).put("latestRequest", run.goal());
        state.putArray("constraints").addObject().put("status", "active")
                .put("value", "NECESSARY_LAYER_" + "c".repeat(900_000));
        when(repository.workingState(any(), any())).thenReturn(state);
        when(repository.listSteps(any(), any())).thenReturn(List.of(latestModelRequest(2)));
        var coordinator = coordinator(repository, routing, run,
                new AgentContextProperties(true, 50_000, 8_000, 2_000, Map.of("model-A", 1_000_000)));

        var outcome = coordinator.advance(run);

        assertThat(calls).as("未发生辅助出站时只有一次主请求").hasSize(1);
        assertThat(calls.getFirst()).isEqualTo("model-A");
        assertThat(resolves.get()).as("未发生辅助出站时保持每请求一次解析").isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    /** 提交事实必须对应真实有效发布：条件转换零行（fenced）不算提交。 */
    @Test
    void fencedRunContextPublicationIsNotReportedAsCommitted() {
        AgentRunView run = run(0);
        when(repository.listSteps(any(), any())).thenReturn(List.of(
                toolStep(1, "read_document_section", "evidence"), latestModelRequest(2)));
        when(repository.beginRunContextAttempt(any(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(UUID.randomUUID());
        when(repository.completeRunContextAttempt(any(), anyString(), anyString(), any(), anyString(), any(), anyInt()))
                .thenReturn(false); // 条件 UPDATE 转换零行：fenced / 重复完成
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any()))
                .thenReturn(turn("A qualifying run-context summary text.", ModelFinishReason.STOP));
        var budget = new AgentContextBudget.Budget(50_000, 50_000, 42_500, 21_250,
                AgentContextBudget.BINDING_PER_REQUEST_CAP, true);

        var outcome = summarizer.maybeSummarizeDetailed(run, null, Integer.MAX_VALUE, Integer.MAX_VALUE,
                budget, 60_000, () -> true);

        assertThat(outcome.committed()).as("fenced 发布不算提交，不能只因调用了 complete 就声称成功").isFalse();
        assertThat(outcome.auxiliaryAttempted()).as("请求已真实出站").isTrue();
    }

    // ================================================================
    // E3：辅助实际发生后按新快照重建本次主请求
    // ================================================================

    /**
     * 大窗口 A 切小窗口 B：辅助失败后必须按 B 的窗口重建消息视图，
     * 不能继续发送只装得下 A 的旧历史再直接判超限。
     */
    @Test
    void failedAuxiliaryRebuildsMainRequestForSmallerWindow() {
        var store = mock(AgentModelConfigurationStore.class);
        when(store.require(any())).thenReturn(provider("model-A"), provider("model-B"));
        var calls = new ArrayList<String>();
        var mainOutbound = new ArrayList<List<ModelMessage>>();
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            List<ModelMessage> messages = invocation.getArgument(0);
            calls.add(AiConfigurationContext.current().modelName());
            if (isAuxiliaryRequest(messages)) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "Controlled auxiliary failure");
            }
            mainOutbound.add(messages);
            return turn("A complete answer.", ModelFinishReason.STOP);
        });
        var routing = controlledRouting(nativeExecutor, store);
        AgentRunView run = run(0);
        var repository = coordinatorRepository(run);
        repositoryState(repository, run);
        // 可选旧历史：只有大窗口 A 装得下；B 必须重新组装而不是沿用 A 的结果
        var old = new AgentMessageView(UUID.randomUUID(), run.sessionId(), run.id(), "ASSISTANT",
                "OLD_HISTORY_ONLY_FITS_A_" + "h".repeat(90_000), null, null,
                OffsetDateTime.now().minusMinutes(5));
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(List.of(old));
        when(repository.listSummaryCandidates(any(), any(), anyInt())).thenReturn(List.of(
                new AgentMessageView(UUID.randomUUID(), run.sessionId(), run.id(), "USER",
                        "An earlier explicit decision must remain available", null, null,
                        OffsetDateTime.now().minusDays(1))));
        var coordinator = coordinator(repository, routing, run, new AgentContextProperties(
                true, 50_000, 8_000, 2_000, Map.of("model-A", 1_000_000, "model-B", 20_000)));

        var outcome = coordinator.advance(run);

        assertThat(outcome.status())
                .as("辅助失败后按 B 窗口可重组时不得直接停成 BUDGET_EXCEEDED")
                .isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(calls).as("一次辅助出站 + 一次主请求").hasSize(2);
        assertThat(calls.getLast()).isEqualTo("model-B");
        assertThat(mainOutbound).hasSize(1);
        String outbound = mainOutbound.getFirst().toString();
        assertThat(outbound)
                .as("主请求必须按 B 的窗口重新组装，不沿用只装得下 A 的旧历史")
                .doesNotContain("OLD_HISTORY_ONLY_FITS_A_");
        assertThat(outbound).as("重建后本轮收尾/格式指令层保留").contains("单轮工具调用最多");
    }

    /**
     * 必要层真实超过 H 时仍明确收口：不得为了"让请求通过"放宽窗口或屏蔽超限。
     * 工作状态（必要层）在小窗口 B 下装不下，辅助失败后重建必须如实 BUDGET_EXCEEDED，
     * 且不得再发出主请求。
     */
    @Test
    void necessaryLayerExceedingSmallWindowStillClosesExplicitly() {
        var store = mock(AgentModelConfigurationStore.class);
        when(store.require(any())).thenReturn(provider("model-A"), provider("model-B"));
        var calls = new ArrayList<String>();
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            List<ModelMessage> messages = invocation.getArgument(0);
            calls.add(AiConfigurationContext.current().modelName());
            if (isAuxiliaryRequest(messages)) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "Controlled auxiliary failure");
            }
            return turn("A complete answer.", ModelFinishReason.STOP);
        });
        var routing = controlledRouting(nativeExecutor, store);
        AgentRunView run = run(0);
        var repository = coordinatorRepository(run);
        // 必要层（工作状态）真实超过 B 的安全可用输入
        var state = JSON.createObjectNode().put("schemaVersion", 2).put("stateRevision", 1)
                .put("goalRevision", 0).put("activeGoal", run.goal()).put("latestRequest", run.goal());
        state.putArray("constraints").addObject().put("status", "active")
                .put("value", "NECESSARY_LAYER_" + "c".repeat(800_000));
        when(repository.workingState(any(), any())).thenReturn(state);
        when(repository.listSteps(any(), any())).thenReturn(List.of(
                toolStep(1, "read_document_section", "bounded evidence " + "e".repeat(3_000)),
                latestModelRequest(2)));
        when(repository.listSummaryCandidates(any(), any(), anyInt())).thenReturn(List.of());
        var coordinator = coordinator(repository, routing, run, new AgentContextProperties(
                true, 50_000, 8_000, 2_000, Map.of("model-A", 1_000_000, "model-B", 40_000)));

        var outcome = coordinator.advance(run);

        assertThat(outcome.status()).as("必要层真实超过 H 时明确收口").isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_BUDGET_EXCEEDED");
        assertThat(calls).as("超出必要层时不再发出主请求，只发生过辅助出站").hasSize(1);
    }

    /**
     * Native → Legacy 的模式切换同样按新快照重建：辅助失败后主请求必须使用新的只读协议契约，
     * 而不是继续沿用按 Native 模式组装的消息与出站路径。
     */
    @Test
    void auxiliaryFailureSwitchingToLegacyRebuildsReadOnlyContract() {
        var store = mock(AgentModelConfigurationStore.class);
        when(store.require(any())).thenReturn(
                provider("model-A"),
                provider("model-B-legacy", java.util.EnumSet.of(ModelCapability.CHAT)));
        var legacyOutbound = new ArrayList<List<ModelMessage>>();
        var legacy = mock(LegacyReadOnlyAgentExecutor.class);
        when(legacy.callModel(anyList(), anyList(), any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            legacyOutbound.add(invocation.getArgument(0));
            return turn("只读回答", ModelFinishReason.STOP);
        });
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "Controlled auxiliary failure"));
        var zen = mock(ZenModelExecution.class);
        when(zen.isZen(any())).thenReturn(false);
        var routing = new RoutingAgentModelExecutor(nativeExecutor, legacy, zen, store);

        AgentRunView run = run(0);
        var repository = coordinatorRepository(run);
        repositoryState(repository, run);
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(List.of(
                new AgentMessageView(UUID.randomUUID(), run.sessionId(), run.id(), "ASSISTANT",
                        "h".repeat(30_000), null, null, OffsetDateTime.now().minusMinutes(5))));
        when(repository.listSummaryCandidates(any(), any(), anyInt())).thenReturn(List.of(
                new AgentMessageView(UUID.randomUUID(), run.sessionId(), run.id(), "USER",
                        "An earlier explicit decision must remain available", null, null,
                        OffsetDateTime.now().minusDays(1))));
        var coordinator = coordinator(repository, routing, run, new AgentContextProperties(
                true, 50_000, 8_000, 2_000, Map.of("model-A", 1_000_000, "model-B-legacy", 200_000)));

        var outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(legacyOutbound).as("主请求必须按新快照走 Legacy 出站路径").hasSize(1);
        assertThat(legacyOutbound.getFirst().toString())
                .as("重建后的消息视图必须携带新的 Legacy 只读契约")
                .contains("当前运行模式：Legacy（只读）");
    }

    // ---------------------------------------------------------------- D5/D8 共用内核

    private record Kernel(AgentRunView run, AgentRepository repository, AgentRuntimeCoordinator coordinator) {}

    /** 协调器装配：真实 Composer/Summarizer/Routing，受控仓库与出站。 */
    private AgentRuntimeCoordinator coordinator(AgentRepository repository,
            RoutingAgentModelExecutor executor, AgentRunView run, AgentContextProperties properties) {
        // 先在 stubbing 之外构造受控 Skill，避免嵌套 stubbing（UnfinishedStubbing）
        var skill = skill(run);
        var assembledContext = context(run);
        var assembler = mock(AgentContextAssembler.class);
        when(assembler.assemble(any(), any(), any())).thenReturn(assembledContext);
        var registry = mock(com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry.class);
        when(registry.select(any(), any(), any())).thenReturn(skill);
        var planService = mock(AgentPlanService.class);
        when(planService.ensurePlan(any(), any())).thenReturn(AgentPlan.create(run.goal(), List.of()));
        var composer = new AgentModelMessageComposer(repository, null, JSON);
        var contextSummarizer = new AgentContextSummarizer(repository, executor, JSON, properties);
        return new AgentRuntimeCoordinator(repository, assembler, registry, planService,
                new AgentToolRegistry(List.of()), new AgentCancellationService(repository), executor,
                new AgentConvergencePolicy(), JSON, null, properties, composer,
                mock(AgentToolCallExecutor.class), contextSummarizer);
    }

    /** 会话摘要在本类多个用例中需要的 v2 工作状态。 */
    private static void repositoryState(AgentRepository repository, AgentRunView run) {
        var state = JSON.createObjectNode().put("schemaVersion", 2).put("stateRevision", 1)
                .put("goalRevision", 0).put("activeGoal", run.goal()).put("latestRequest", run.goal());
        state.putArray("constraints");
        when(repository.workingState(any(), any())).thenReturn(state);
    }

    /** 辅助出站判定：会话摘要/重压缩/RUN_CONTEXT 压缩的系统提示都以"你是受控"开头。 */
    private static boolean isAuxiliaryRequest(List<ModelMessage> messages) {
        return !messages.isEmpty() && messages.getFirst().toString().contains("你是受控");
    }
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
