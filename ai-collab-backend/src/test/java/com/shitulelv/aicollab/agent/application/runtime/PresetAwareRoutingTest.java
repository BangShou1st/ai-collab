package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentRuntimeMetrics;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Stale DB capabilities must never steer Zen routing; policy capabilities decide Native vs Legacy. */
class PresetAwareRoutingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    /** J1-1-P1：执行协议必须可从指标读出（native / LEGACY_READ_ONLY 两条独立序列）。 */
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach void closeRegistry() { registry.close(); }

    /** 模拟适配器真实的出站信号：没有它 AgentRuntimeMetrics 不会记这条请求。 */
    private static <T> T outbound(T value) {
        com.shitulelv.aicollab.infrastructure.ai.model.AiOutboundObservation.markStarted(
                com.shitulelv.aicollab.infrastructure.ai.model.AiOutboundObservation.current());
        return value;
    }

    private UserAiProvider staleZen(UUID user) {
        return new UserAiProvider(UUID.randomUUID(), user, "OpenCode Zen Free", ModelProviderType.OPENAI_COMPATIBLE,
                "https://evil.example", "/evil", "enc", "mimo-v2.5-free", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT), false, OffsetDateTime.now(), OffsetDateTime.now(), "OPENCODE_ZEN_FREE");
    }

    private UserAiProvider chatOnlyCustom(UUID user) {
        return new UserAiProvider(UUID.randomUUID(), user, "custom", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "/v1/chat/completions", "enc", "m", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT), false, OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    private AgentRunView run(UUID project, UUID requester) {
        return new AgentRunView(UUID.randomUUID(), UUID.randomUUID(), project, requester, null, "agent",
                0, "goal", AgentRunStatus.RUNNING, 12, 16, 3, 100000, 8000, 0, 0, 0, 0, 0, 0, 0, false, false,
                false, 0, null, null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private RoutingAgentModelExecutor executor(AgentModelConfigurationStore store,
            NativeToolCallingExecutor nativeExec, LegacyReadOnlyAgentExecutor legacyExec) {
        var executor = new RoutingAgentModelExecutor(nativeExec, legacyExec,
                new ZenModelExecution(new ProviderPresetRegistry(), mapper, new OutboundEndpointPolicy()), store);
        ReflectionTestUtils.setField(executor, "metrics", new AgentRuntimeMetrics(registry));
        return executor;
    }

    @Test void staleZenCapabilitiesStillRouteToNative() {
        var user = UUID.randomUUID();
        var request = run(UUID.randomUUID(), user);
        var store = mock(AgentModelConfigurationStore.class);
        when(store.require(request)).thenReturn(staleZen(user));
        var nativeExec = mock(NativeToolCallingExecutor.class);
        var legacyExec = mock(LegacyReadOnlyAgentExecutor.class);
        ModelTurnResult result = mock(ModelTurnResult.class);
        when(nativeExec.callModel(anyList(), anyList(), any(), any(), any()))
                .thenAnswer(invocation -> outbound(result));
        var executor = executor(store, nativeExec, legacyExec);
        var out = executor.callModel(request, List.of(), List.of(), false, executor.resolveRequest(request));
        assertThat(out).isEqualTo(result);
        verify(legacyExec, never()).callModel(anyList(), anyList(), any(), any(), anyBoolean(), any());
        // 原生分支的轮次只能记在 NATIVE_TOOLS 序列上
        assertThat(registry.get("agent.model.request")
                .tags("purpose","turn","mode","NATIVE_TOOLS","outcome","success").counter().count()).isEqualTo(1);
        assertThat(registry.find("agent.model.request").tag("mode","LEGACY_READ_ONLY").counter()).isNull();
    }

    @Test void chatOnlyCustomStillRoutesToLegacy() {
        var user = UUID.randomUUID();
        var request = run(UUID.randomUUID(), user);
        var store = mock(AgentModelConfigurationStore.class);
        when(store.require(request)).thenReturn(chatOnlyCustom(user));
        var nativeExec = mock(NativeToolCallingExecutor.class);
        var legacyExec = mock(LegacyReadOnlyAgentExecutor.class);
        ModelTurnResult result = mock(ModelTurnResult.class);
        when(legacyExec.callModel(anyList(), anyList(), any(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> outbound(result));
        var executor = executor(store, nativeExec, legacyExec);
        var out = executor.callModel(request, List.of(), List.of(), false, executor.resolveRequest(request));
        assertThat(out).isEqualTo(result);
        verify(nativeExec, never()).callModel(anyList(), anyList(), any(), any(), any());
        // CHAT-only 分支的轮次必须落在 LEGACY_READ_ONLY 序列上——这是 J1-1 统计的唯一指标依据
        assertThat(registry.get("agent.model.request")
                .tags("purpose","turn","mode","LEGACY_READ_ONLY","outcome","success").counter().count()).isEqualTo(1);
        assertThat(registry.find("agent.model.request").tag("mode","NATIVE_TOOLS").counter()).isNull();
    }
}
