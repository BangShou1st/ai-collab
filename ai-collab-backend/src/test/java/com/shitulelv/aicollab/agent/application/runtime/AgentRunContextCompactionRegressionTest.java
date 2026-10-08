package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPlan;
import com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ZenModelExecution;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RUN_CONTEXT 窗口压缩闭环回归（探针 CapacityReviewProbe 的正式化，2026-10-08 审查 C1/C2/C3）。
 *
 * <p>使用真实生产组件：真实 {@link AgentModelMessageComposer}、真实 {@link AgentContextSummarizer}、
 * 真实 {@link RoutingAgentModelExecutor}（仅 NativeToolCallingExecutor / 配置存储为受控替身）；
 * Repository 为 Mockito mock。不反射私有方法、不调用真实模型、不访问数据库。</p>
 *
 * <p>基线（ef2a514，未修复）上 1/2/3 号用例为红灯（探针已归档实测 3/3 失败），
 * 修复后转绿；4 号用例锁定"压缩触发按裁剪前活跃来源估算"。</p>
 */
class AgentRunContextCompactionRegressionTest {
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
        model = controlledModel();
        summarizer = new AgentContextSummarizer(repository, model, JSON);
    }

    /** 真实路由执行器：配置存储返回受控 provider，出站捕获发生在 nativeExecutor 边界。 */
    private RoutingAgentModelExecutor controlledModel() {
        var now = OffsetDateTime.now();
        var provider = new UserAiProvider(
                UUID.randomUUID(), UUID.randomUUID(), "test-native", ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.invalid", "/v1/chat/completions", "encrypted", "test-native",
                true, 0.2, 1024,
                java.util.EnumSet.of(ModelCapability.NATIVE_TOOLS, ModelCapability.CHAT),
                true, now, now, null);
        var store = mock(AgentModelConfigurationStore.class);
        when(store.require(any())).thenReturn(provider);
        var zen = mock(ZenModelExecution.class);
        when(zen.isZen(any())).thenReturn(false);
        return new RoutingAgentModelExecutor(nativeExecutor,
                mock(LegacyReadOnlyAgentExecutor.class), zen, store);
    }

    private AgentRunView run() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "Research document evidence", AgentRunStatus.RUNNING,
                64, 64, 3, null, null, 0, 0, 0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, "PROJECT_RESEARCH", 1, 2, false, now, now);
    }

    private AgentExecutionContext context(AgentRunView run) {
        return new AgentExecutionContext(run.projectId(), run.sessionId(), run.id(), run.requesterId(),
                "SUPERVISOR", false, AgentPageContext.empty(), AgentResourcePolicy.v2Limits(0), 0, List.of());
    }

    private AgentSkill skill() {
        AgentSkill skill = mock(AgentSkill.class);
        when(skill.code()).thenReturn("PROJECT_RESEARCH");
        when(skill.instruction()).thenReturn("Read only document research");
        when(skill.outputContract()).thenReturn("Evidence and gaps");
        return skill;
    }

    private AgentStepView toolStep(int sequence, String body) {
        return new AgentStepView(UUID.randomUUID(), sequence, AgentStepType.TOOL_CALL_COMPLETED,
                "read_document_section", JSON.createObjectNode().put("toolCallId", "call-" + sequence),
                JSON.createObjectNode().put("status", "SUCCEEDED").put("content", body),
                "TOOL_SUCCESS", null, null, false, null, null, OffsetDateTime.now());
    }

    private AgentStepView latestModelRequest(int sequence) {
        return new AgentStepView(UUID.randomUUID(), sequence, AgentStepType.MODEL_REQUEST,
                null, null, null, "latest", null, null, false, null, null, OffsetDateTime.now());
    }

    private void stubSummaryTurn(String text) {
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any()))
                .thenReturn(new ModelTurnResult(text, List.of(), ModelFinishReason.STOP,
                        new ModelUsage(20_000, 15), "controlled", "controlled", 1L));
    }

    private AgentContextBudget.Budget largeWindowBudget() {
        return new AgentContextBudget.Budget(950_000, 950_000, 256_000, 128_000,
                AgentContextBudget.BINDING_MODEL_WINDOW, false);
    }

    // ========== 1.（C1）已提交摘要进入实际组装消息，覆盖前缀被替换 ==========

    @Test
    void committedRunContextSummaryReachesComposerAndReplacesCoveredPrefix() {
        AgentRunView run = run();
        when(repository.latestRunContextSummary(any(), any())).thenReturn(JSON.createObjectNode()
                .put("scope", "RUN_CONTEXT").put("status", "COMMITTED")
                .put("text", "SUMMARY_ONLY_FACT_4907")
                .put("sourceFromSequence", 1).put("sourceThroughSequence", 5));

        AgentStepView covered = toolStep(1, "COVERED_RAW_CONTENT_SHOULD_NOT_REAPPEAR");
        AgentStepView recent = toolStep(9, "RECENT_RAW_FACT_5311");
        var composer = new AgentModelMessageComposer(repository, null, JSON);
        var composition = composer.composeV2(run, context(run), skill(),
                AgentPlan.create(run.goal(), List.of()), List.of(covered, recent), 950_000, 1.0, false);

        String request = composition.messages().toString();
        assertThat(composition.failureReason()).isNull();
        // 摘要真正进入实际模型消息（生成→落库→消费闭环）
        assertThat(request).contains("SUMMARY_ONLY_FACT_4907");
        // 未覆盖的近期原文保留
        assertThat(request).contains("RECENT_RAW_FACT_5311");
        // 覆盖前缀被替换出活跃视图
        assertThat(request).doesNotContain("COVERED_RAW_CONTENT_SHOULD_NOT_REAPPEAR");
    }

    // ========== 2.（C3）截断来源按结构化范围记录 partial，尾部留在未覆盖来源 ==========

    @Test
    void unsentTailStaysUncoveredWithPartialOffsetInsteadOfWholeStepCoverage() {
        AgentRunView run = run();
        AgentStepView huge = toolStep(1, "BEGIN_MARKER" + "x".repeat(80_000) + "UNSENT_TAIL_7193");
        when(repository.listSteps(any(), any())).thenReturn(List.of(huge, latestModelRequest(2)));
        stubSummaryTurn("受控摘要：仅覆盖送入前缀");

        boolean committed = summarizer.compactRunContext(run, null, largeWindowBudget(),
                Integer.MAX_VALUE, () -> true);

        // 尾部没有进入摘要请求
        ArgumentCaptor<List<ModelMessage>> request = ArgumentCaptor.forClass(List.class);
        verify(nativeExecutor).callModel(request.capture(), anyList(), any(), any(), any());
        assertThat(committed).isTrue();
        assertThat(request.getValue().toString()).doesNotContain("UNSENT_TAIL_7193");

        // 提交范围按结构化记录边界记录 partial 偏移，不虚报整条覆盖
        ArgumentCaptor<JsonNode> summary = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).completeRunContextAttempt(any(), eq("COMMITTED"), anyString(),
                any(), anyString(), summary.capture(), eq(0));
        assertThat(summary.getValue().path("sourcePartialSequence").asInt(0)).isEqualTo(1);
        assertThat(summary.getValue().path("sourcePartialChars").asInt(0)).isGreaterThan(0);

        // 未送入模型的尾部保留在未覆盖来源中，下一周期从偏移继续处理
        when(repository.latestRunContextSummary(any(), any())).thenReturn(summary.getValue());
        assertThat(summarizer.compressibleSourceSteps(run))
                .extracting(AgentStepView::sequence)
                .contains(1);
    }

    // ========== 3.（C2）有真实窗口空间时旧正文不被固定投影裁掉 ==========

    @Test
    void largeWindowKeepsOlderRawEvidenceWithoutPressureProjection() {
        AgentRunView run = run();
        AgentStepView older = toolStep(1, "OLD_PREFIX" + "x".repeat(24_000) + "OLDER_REQUIRED_FACT_8711");
        AgentStepView latest = toolStep(2, "Recent independent observation");
        var composer = new AgentModelMessageComposer(repository, null, JSON);
        var composition = composer.composeV2(run, context(run), skill(),
                AgentPlan.create(run.goal(), List.of()), List.of(older, latest), 950_000, 1.0, false);

        assertThat(composition.failureReason()).isNull();
        // 有空间时旧正文必要尾部不消失，也不做投影
        assertThat(composition.messages().toString()).contains("OLDER_REQUIRED_FACT_8711");
        assertThat(composition.stats().toolResultsProjected()).isZero();
    }

    // ========== 4.（C2）压缩触发按裁剪前活跃来源估算，不按裁后体积 ==========

    @Test
    void compactionTriggersOnPreClipActiveSourcesEvenWhenClippedCompositionLooksSmall() {
        AgentRunView run = run();
        // 一条 400k 字符的工具结果：预算 40k token（约 12 万字符）时被投影，
        // 裁后组装体积远小于触发线，但裁前活跃来源远超触发线。
        AgentStepView huge = toolStep(1, "PRESSURE_SOURCE" + "y".repeat(400_000));
        when(repository.listSteps(any(), any())).thenReturn(List.of(huge, latestModelRequest(2)));
        stubSummaryTurn("受控摘要：压力触发");

        var composer = new AgentModelMessageComposer(repository, null, JSON);
        var composition = composer.composeV2(run, context(run), skill(),
                AgentPlan.create(run.goal(), List.of()), List.of(huge), 40_000, 1.0, false);
        assertThat(composition.failureReason()).isNull();
        assertThat(composition.stats().droppedSourceChars()).isPositive();

        // 触发线 T 位于裁后估算与裁前估算之间：裁后不触发，裁前必须触发
        var budget = new AgentContextBudget.Budget(40_000, 950_000, 100_000, 50_000,
                AgentContextBudget.BINDING_MODEL_WINDOW, false);
        summarizer.maybeSummarize(run, composition, Integer.MAX_VALUE, Integer.MAX_VALUE,
                budget, () -> true);

        verify(nativeExecutor, times(1)).callModel(anyList(), anyList(), any(), any(), any());
    }
}
