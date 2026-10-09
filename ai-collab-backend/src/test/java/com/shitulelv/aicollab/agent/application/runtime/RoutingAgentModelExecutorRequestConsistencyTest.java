package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.infrastructure.ai.model.AiConfigurationContext;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ZenModelExecution;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单次模型请求配置一致性：请求准备时解析一次当前 AGENT 配置，同一份配置
 * 用于能力判断与实际出站调用；准备之后切换配置不影响本次请求，下一次请求才采用新配置。
 */
class RoutingAgentModelExecutorRequestConsistencyTest {

    private NativeToolCallingExecutor nativeExecutor;
    private LegacyReadOnlyAgentExecutor legacyExecutor;
    private AgentModelConfigurationStore store;
    private RoutingAgentModelExecutor executor;
    private AgentRunView run;

    @BeforeEach
    void setUp() {
        store = mock(AgentModelConfigurationStore.class);
        nativeExecutor = mock(NativeToolCallingExecutor.class);
        legacyExecutor = mock(LegacyReadOnlyAgentExecutor.class);
        var zen = mock(ZenModelExecution.class);
        when(zen.isZen(any())).thenReturn(false);
        executor = new RoutingAgentModelExecutor(nativeExecutor, legacyExecutor, zen, store);
        run = new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查本周任务是否影响交付", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private UserAiProvider provider(String modelName, boolean nativeTools) {
        var capabilities = nativeTools
                ? EnumSet.of(ModelCapability.NATIVE_TOOLS, ModelCapability.CHAT)
                : EnumSet.of(ModelCapability.CHAT);
        return new UserAiProvider(UUID.randomUUID(), run.requesterId(), modelName,
                ModelProviderType.OPENAI_COMPATIBLE, "https://example.com", "/v1/chat/completions",
                "enc", modelName, true, 0.2, 1024, capabilities, true,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void configurationResolvedOncePerRequestSurvivesMidFlightSwitch() {
        var modelA = provider("acceptance-a", true);
        var modelB = provider("acceptance-b", false);
        AtomicInteger resolves = new AtomicInteger();
        when(store.require(run)).thenAnswer(inv -> resolves.incrementAndGet() == 1 ? modelA : modelB);
        AtomicReference<UUID> outboundProvider = new AtomicReference<>();
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenAnswer(inv -> {
            outboundProvider.set(AiConfigurationContext.current().id());
            return new ModelTurnResult("已处理", List.of(),
                    com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason.STOP, null,
                    "OPENAI_COMPATIBLE", "acceptance-a", 5L);
        });

        // 第 1 次请求：按当前配置 A 准备（仅一次解析）
        var resolved = executor.resolveRequest(run);
        assertThat(resolved.provider().id()).isEqualTo(modelA.id());
        assertThat(resolved.legacyMode()).isFalse();
        assertThat(resolves.get()).isEqualTo(1);

        // 准备之后用户把 AGENT 用途切换到 Legacy B：本次请求不受影响
        var turn = executor.callModel(run, List.of(new ModelMessage.User("检查")), List.of(), false, resolved);
        assertThat(turn).isNotNull();
        assertThat(outboundProvider.get()).isEqualTo(modelA.id());
        assertThat(resolves.get()).isEqualTo(1);

        // 下一次请求准备时才读取新配置：Legacy B
        var next = executor.resolveRequest(run);
        assertThat(next.provider().id()).isEqualTo(modelB.id());
        assertThat(next.legacyMode()).isTrue();
        assertThat(resolves.get()).isEqualTo(2);
    }

    @Test
    void switchedLegacyConfigurationRoutesNextRequestThroughLegacyExecutor() {
        var modelA = provider("acceptance-a", true);
        var modelB = provider("acceptance-b", false);
        when(store.require(run)).thenReturn(modelA, modelB);
        when(legacyExecutor.callModel(anyList(), anyList(), any(), any(), anyBoolean(), any()))
                .thenReturn(new ModelTurnResult("已处理", List.of(),
                        com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason.STOP, null,
                        "OPENAI_COMPATIBLE", "acceptance-b", 5L));

        // 原生 A 在途：本次请求仍按 A 走原生执行器
        var resolved = executor.resolveRequest(run);
        assertThat(resolved.legacyMode()).isFalse();
        var turnInFlight = new ModelTurnResult("我先读取项目任务。", List.of(),
                com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason.TOOL_CALLS, null,
                "OPENAI_COMPATIBLE", "acceptance-a", 5L);
        when(nativeExecutor.callModel(anyList(), anyList(), any(), any(), any())).thenReturn(turnInFlight);
        assertThat(executor.callModel(run, List.of(new ModelMessage.User("检查")), List.of(), false, resolved))
                .isSameAs(turnInFlight);

        // 下一次请求采用 Legacy B：走只读执行器，写工具在协议层已不可用
        var next = executor.resolveRequest(run);
        assertThat(executor.callModel(run, List.of(new ModelMessage.User("检查")), List.of(), false, next))
                .isNotSameAs(turnInFlight);
        verify(legacyExecutor).callModel(anyList(), anyList(),
                eq(run.projectId()), eq(run.requesterId()), eq(false), any());
    }
}
