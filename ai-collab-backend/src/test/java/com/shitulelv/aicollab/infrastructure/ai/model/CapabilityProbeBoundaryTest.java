package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 能力探针专用执行边界（CapabilityProbeContext）：
 * <ul>
 *   <li>探针上下文中，未声明 NATIVE_TOOLS / STREAMING 的自定义模型可以发起真实合成请求；</li>
 *   <li>生产路径（无探针上下文）声明门控保持原样；</li>
 *   <li>跨 Provider / 非探针来源不享受豁免。</li>
 * </ul>
 * 对应 P7-PROVIDER-ONBOARDING-01 任务 B2 / 验收场景 T01。
 */
class CapabilityProbeBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private UserAiProvider provider(EnumSet<ModelCapability> capabilities) {
        OffsetDateTime now = OffsetDateTime.now();
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "c",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com", "/v1/chat/completions",
                "enc", "m", true, 0.2, 1200, capabilities, false, now, now, null);
    }

    private RoutingModelTurnGateway turnGateway(UserAiProviderService providers, ModelTurnProviderAdapter adapter) {
        return new RoutingModelTurnGateway(providers, mock(ModelSecretCipher.class), List.of(adapter));
    }

    private RoutingChatModelGateway chatGateway(UserAiProviderService providers, ModelProviderAdapter adapter) {
        return new RoutingChatModelGateway(providers, mock(ModelSecretCipher.class),
                List.of(adapter), JSON);
    }

    private ModelTurnCommand turnCommand(UUID userId) {
        return new ModelTurnCommand(ModelPurpose.AGENT, null, null,
                List.of(new com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage.User("probe")),
                List.of(new ModelToolDefinition("capability_probe", "synthetic", JSON.createObjectNode())),
                true, userId);
    }

    @Test void undeclaredNativeToolsProbeReachesProviderInsideProbeContext() {
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT));
        ModelTurnProviderAdapter adapter = mock(ModelTurnProviderAdapter.class);
        when(adapter.providerType()).thenReturn(ModelProviderType.OPENAI_COMPATIBLE);
        when(adapter.turn(any(), org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(new ModelTurnResult(
                "", List.of(new com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall(
                        "t1", "capability_probe", JSON.createObjectNode().put("value", "probe"), null)),
                null, null, "OPENAI_COMPATIBLE", "m", 5L, null));
        UserAiProviderService providers = mock(UserAiProviderService.class);
        RoutingModelTurnGateway gateway = turnGateway(providers, adapter);

        ModelTurnResult result;
        try (var pinned = new AiConfigurationContext(snapshot);
             var probe = new CapabilityProbeContext(snapshot)) {
            result = gateway.turn(turnCommand(snapshot.userId()));
        }
        assertThat(result.toolCalls()).hasSize(1);
        verify(adapter).turn(any(), org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test void undeclaredNativeToolsStillRejectedOutsideProbeContext() {
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT));
        ModelTurnProviderAdapter adapter = mock(ModelTurnProviderAdapter.class);
        when(adapter.providerType()).thenReturn(ModelProviderType.OPENAI_COMPATIBLE);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        RoutingModelTurnGateway gateway = turnGateway(providers, adapter);

        try (var pinned = new AiConfigurationContext(snapshot)) {
            assertThatThrownBy(() -> gateway.turn(turnCommand(snapshot.userId())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("NATIVE_TOOLS");
        }
    }

    @Test void probeContextForAnotherProviderDoesNotBypassTheGate() {
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT));
        UserAiProvider other = provider(EnumSet.of(ModelCapability.CHAT));
        ModelTurnProviderAdapter adapter = mock(ModelTurnProviderAdapter.class);
        when(adapter.providerType()).thenReturn(ModelProviderType.OPENAI_COMPATIBLE);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        RoutingModelTurnGateway gateway = turnGateway(providers, adapter);

        try (var pinned = new AiConfigurationContext(snapshot);
             var probe = new CapabilityProbeContext(other)) {
            assertThatThrownBy(() -> gateway.turn(turnCommand(snapshot.userId())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("NATIVE_TOOLS");
        }
    }

    @Test void undeclaredStreamingProbeReachesProviderInsideProbeContext() {
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT));
        ModelProviderAdapter adapter = mock(ModelProviderAdapter.class);
        when(adapter.providerType()).thenReturn(ModelProviderType.OPENAI_COMPATIBLE);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        RoutingChatModelGateway gateway = chatGateway(providers, adapter);

        try (var pinned = new AiConfigurationContext(snapshot);
             var probe = new CapabilityProbeContext(snapshot)) {
            gateway.completeStream(new ChatCompletionCommand(null, null, "probe",
                            ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT,
                            null, List.of(), snapshot.userId()),
                    token -> {}, result -> {}, error -> {});
            verify(adapter).completeStream(any(), org.mockito.ArgumentMatchers.isNull(), any(), any(), any(), any());
        }
    }

    @Test void undeclaredStreamingStillRejectedOutsideProbeContext() {
        UserAiProvider snapshot = provider(EnumSet.of(ModelCapability.CHAT));
        ModelProviderAdapter adapter = mock(ModelProviderAdapter.class);
        when(adapter.providerType()).thenReturn(ModelProviderType.OPENAI_COMPATIBLE);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        RoutingChatModelGateway gateway = chatGateway(providers, adapter);

        try (var pinned = new AiConfigurationContext(snapshot)) {
            gateway.completeStream(new ChatCompletionCommand(null, null, "probe",
                            ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT,
                            null, List.of(), snapshot.userId()),
                    token -> {}, result -> {}, error -> {});
            verify(adapter, never()).completeStream(any(), org.mockito.ArgumentMatchers.isNull(), any(), any(), any(), any());
        }
    }
}
