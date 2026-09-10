package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.model.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Stale DB capabilities must never steer Zen routing; policy capabilities decide Native vs Legacy. */
class PresetAwareRoutingTest {
    private final ObjectMapper mapper = new ObjectMapper();

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
                0, "goal", AgentRunStatus.RUNNING, 12, 16, 3, 100000, 8000, 0, 0, 0, 0, 0, false, false,
                false, 0, null, null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private RoutingAgentModelExecutor executor(UserAiProviderService providers,
            NativeToolCallingExecutor nativeExec, LegacyReadOnlyAgentExecutor legacyExec) {
        return new RoutingAgentModelExecutor(providers, nativeExec, legacyExec,
                new ZenModelExecution(new ProviderPresetRegistry(), mapper, new OutboundEndpointPolicy()));
    }

    @Test void staleZenCapabilitiesStillRouteToNative() {
        var user = UUID.randomUUID();
        var providers = mock(UserAiProviderService.class);
        when(providers.resolve(user, ModelPurpose.AGENT)).thenReturn(staleZen(user));
        var nativeExec = mock(NativeToolCallingExecutor.class);
        var legacyExec = mock(LegacyReadOnlyAgentExecutor.class);
        ModelTurnResult result = mock(ModelTurnResult.class);
        when(nativeExec.callModel(anyList(), anyList(), any(), any(), any())).thenReturn(result);
        var out = executor(providers, nativeExec, legacyExec)
                .callModel(run(UUID.randomUUID(), user), List.of(), List.of(), false);
        assertThat(out).isEqualTo(result);
        verify(legacyExec, never()).callModel(anyList(), anyList(), any(), any(), anyBoolean(), any());
    }

    @Test void chatOnlyCustomStillRoutesToLegacy() {
        var user = UUID.randomUUID();
        var providers = mock(UserAiProviderService.class);
        when(providers.resolve(user, ModelPurpose.AGENT)).thenReturn(chatOnlyCustom(user));
        var nativeExec = mock(NativeToolCallingExecutor.class);
        var legacyExec = mock(LegacyReadOnlyAgentExecutor.class);
        ModelTurnResult result = mock(ModelTurnResult.class);
        when(legacyExec.callModel(anyList(), anyList(), any(), any(), anyBoolean(), any())).thenReturn(result);
        var out = executor(providers, nativeExec, legacyExec)
                .callModel(run(UUID.randomUUID(), user), List.of(), List.of(), false);
        assertThat(out).isEqualTo(result);
        verify(nativeExec, never()).callModel(anyList(), anyList(), any(), any(), any());
    }
}
