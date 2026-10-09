package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ZenModelExecution;
import com.shitulelv.aicollab.infrastructure.ai.model.AiRequestOutputCap;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 辅助模型出站统一规则回归（审查 2026-10-08 C5）。
 *
 * <p>摘要 / 重压缩 / RUN_CONTEXT 压缩是实际出站的模型请求，必须与主请求遵守同一套
 * 出站规则：本次配置快照、模型窗口、实际单次输出封顶与有界续租。真实
 * {@link RoutingAgentModelExecutor}，仅 native 执行器与配置存储为受控替身。</p>
 */
class AgentAuxiliaryOutboundRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private AgentRepository repository;
    private NativeToolCallingExecutor nativeExecutor;
    private RoutingAgentModelExecutor model;
    private AgentContextSummarizer summarizer;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        // E1：发布接口返回"是否真实发布"的业务事实；本类模拟健康持久层，条件转换成功。
        // 真正 fenced/零行的场景由 AgentRunContextCommitPostgresTest 真实 PostgreSQL 覆盖。
        when(repository.completeRunContextAttempt(any(), anyString(), anyString(), any(), anyString(),
                any(), anyInt())).thenReturn(true);
        nativeExecutor = mock(NativeToolCallingExecutor.class);
        model = controlledModel();
        summarizer = new AgentContextSummarizer(repository, model, json());
    }

    private ObjectMapper json() {
        return JSON;
    }

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

    // ========== C5-1：辅助请求在调用期间携带本次快照的单次输出封顶 ==========

    @Test
    void auxiliarySummaryCallCarriesOwnRequestOutputCap() {
        AgentRunView run = run();
        AtomicBoolean capActiveAtCall = new AtomicBoolean(false);
        AtomicReference<Integer> effectiveAtCall = new AtomicReference<>();
        // provider 配置的单次最大输出为 1024：封顶生效时 effective(4096) 被收紧到封顶值
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            capActiveAtCall.set(AiRequestOutputCap.active());
            effectiveAtCall.set(AiRequestOutputCap.effective(4096));
            return new ModelTurnResult("受控摘要：输出封顶验证", List.of(), ModelFinishReason.STOP,
                    new ModelUsage(20_000, 15), "controlled", "controlled", 1L);
        });
        when(repository.listSteps(any(), any())).thenReturn(List.of(
                toolStep(1, "压力来源：" + "x".repeat(30_000)), latestModelRequest(2)));

        var budget = new AgentContextBudget.Budget(950_000, 950_000, 256_000, 128_000,
                AgentContextBudget.BINDING_MODEL_WINDOW, false);
        boolean committed = summarizer.compactRunContext(run, null, budget, Integer.MAX_VALUE, () -> true);

        assertThat(committed).isTrue();
        assertThat(capActiveAtCall.get())
                .as("摘要请求是实际出站调用，必须携带本次快照的单次输出封顶")
                .isTrue();
        assertThat(effectiveAtCall.get()).isEqualTo(1024);
    }

    private com.shitulelv.aicollab.agent.application.view.AgentStepView toolStep(int sequence, String body) {
        return new com.shitulelv.aicollab.agent.application.view.AgentStepView(UUID.randomUUID(), sequence,
                com.shitulelv.aicollab.agent.domain.model.AgentStepType.TOOL_CALL_COMPLETED,
                "read_document_section", JSON.createObjectNode().put("toolCallId", "call-" + sequence),
                JSON.createObjectNode().put("status", "SUCCEEDED").put("content", body),
                "TOOL_SUCCESS", null, null, false, null, null, OffsetDateTime.now());
    }

    private com.shitulelv.aicollab.agent.application.view.AgentStepView latestModelRequest(int sequence) {
        return new com.shitulelv.aicollab.agent.application.view.AgentStepView(UUID.randomUUID(), sequence,
                com.shitulelv.aicollab.agent.domain.model.AgentStepType.MODEL_REQUEST,
                null, null, null, "latest", null, null, false, null, null, OffsetDateTime.now());
    }
    // ========== C5-2：有界续租——续租推进、失去租约即停、停止后不泄漏 ==========

    @Test
    void leaseRenewerRenewsWhileHealthyAndStopsOnLossWithoutLeakingTasks() throws Exception {
        AgentRunView run = run();
        CopyOnWriteArrayList<Integer> renewals = new CopyOnWriteArrayList<>();
        AtomicBoolean healthy = new AtomicBoolean(true);
        when(repository.renewLease(any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    renewals.add(invocation.getArgument(2, Integer.class));
                    return healthy.get();
                });

        try (var scope = new AgentLeaseScope(42);
             var renewer = AgentLeaseRenewer.forCurrentClaim(
                     repository, run.projectId(), run.id(), Duration.ofMillis(30), Duration.ofSeconds(6))) {
            assertThat(renewer.active()).isTrue();
            waitFor("首次续租", () -> !renewals.isEmpty());
            waitFor("持续续租", () -> renewals.size() >= 3);

            // 失去租约（renewLease 返回 false）：立即停止续租
            healthy.set(false);
            waitFor("失去租约后停止", () -> !renewer.active());
            int atStop = renewals.size();
            Thread.sleep(150);
            // 停止后不再出现新的续租调用，定时任务不泄漏
            assertThat(renewals.size()).isEqualTo(atStop);
        }
    }

    /** 轮询等待（毫秒级），不引入额外依赖。 */
    private static void waitFor(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等待超时: " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断: " + what, e);
            }
        }
    }

    // ========== C5-3：非 claim 上下文（无 epoch）不续租，也不抛错 ==========

    @Test
    void renewerWithoutClaimContextIsANoOp() {
        AgentRunView run = run();
        try (var renewer = AgentLeaseRenewer.forCurrentClaim(
                repository, run.projectId(), run.id(), Duration.ofMillis(30), Duration.ofSeconds(6))) {
            assertThat(renewer.active()).isFalse();
        }
        assertThat(repository).isNotNull();
    }
}
