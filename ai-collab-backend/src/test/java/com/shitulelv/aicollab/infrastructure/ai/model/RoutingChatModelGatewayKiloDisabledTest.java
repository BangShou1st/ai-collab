package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2：停用的 Kilo 预设不得发出实际模型请求——同步、流式、用途绑定与默认模型路径都要在出站前拒绝，
 * 启用时的原有行为保持不变。
 */
class RoutingChatModelGatewayKiloDisabledTest {
    private static final String FREE_MODEL = "poolside/laguna-s-2.1:free";

    private UserAiProvider kilo(boolean enabled) {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "Kilo Gateway Free",
                ModelProviderType.OPENAI_COMPATIBLE, "https://ai-gateway.kilo.ai/api/v1", "/chat/completions",
                null, FREE_MODEL, enabled, 0.2, 4000,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE),
                false, OffsetDateTime.now(), OffsetDateTime.now(), "KILO_GATEWAY_FREE");
    }

    private ModelConfiguration configuration(UserAiProvider provider) {
        return new ModelConfiguration(provider.id(), provider.userId(), provider.name(), provider.providerType(),
                provider.baseUrl(), provider.apiPath(), null, provider.modelName(), provider.enabled(),
                provider.temperature(), provider.maxOutputTokens(), provider.capabilities(),
                provider.createdAt(), provider.updatedAt());
    }

    private ChatCompletionCommand command(UUID user, ModelPurpose purpose) {
        return new ChatCompletionCommand(UUID.randomUUID(), "sys", "hi",
                ChatCompletionCommand.OutputFormat.TEXT, purpose, null, List.of(), user);
    }

    private KiloGatewayModelExecution kiloExecution(UserAiProvider provider) {
        KiloGatewayModelExecution kilo = mock(KiloGatewayModelExecution.class);
        when(kilo.isKilo(any())).thenReturn(true);
        when(kilo.runtimeConfig(any())).thenReturn(configuration(provider));
        return kilo;
    }

    private RoutingChatModelGateway gateway(UserAiProviderService service, KiloGatewayModelExecution kilo) {
        ZenModelExecution zen = mock(ZenModelExecution.class);
        when(zen.isZen(any())).thenReturn(false);
        return new RoutingChatModelGateway(service, mock(ModelSecretCipher.class), List.of(),
                new ObjectMapper(), zen, kilo);
    }

    @Test void disabledKiloRejectsSyncCompletionOnTheDefaultModelPath() {
        var user = UUID.randomUUID();
        var disabled = kilo(false);
        var service = mock(UserAiProviderService.class);
        when(service.resolve(user, ModelPurpose.KNOWLEDGE_CHAT)).thenReturn(disabled);
        var kilo = kiloExecution(disabled);

        assertThatThrownBy(() -> gateway(service, kilo)
                .complete(command(user, ModelPurpose.KNOWLEDGE_CHAT), AiRequestMetadata.fresh()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));

        verify(kilo, never()).complete(any(), any());
    }

    @Test void disabledKiloRejectsSyncCompletionOnTheAssignedPurposePath() {
        var user = UUID.randomUUID();
        var disabled = kilo(false);
        var service = mock(UserAiProviderService.class);
        when(service.resolve(user, ModelPurpose.AGENT)).thenReturn(disabled);
        var kilo = kiloExecution(disabled);

        assertThatThrownBy(() -> gateway(service, kilo)
                .complete(command(user, ModelPurpose.AGENT), AiRequestMetadata.fresh()))
                .isInstanceOf(BusinessException.class);

        verify(kilo, never()).complete(any(), any());
    }

    @Test void disabledKiloRejectsStreamingWithoutCallingOnDone() {
        var user = UUID.randomUUID();
        var disabled = kilo(false);
        var service = mock(UserAiProviderService.class);
        when(service.resolve(user, ModelPurpose.KNOWLEDGE_CHAT)).thenReturn(disabled);
        var kilo = kiloExecution(disabled);

        AtomicInteger done = new AtomicInteger();
        List<Exception> errors = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        gateway(service, kilo).completeStream(command(user, ModelPurpose.KNOWLEDGE_CHAT),
                AiRequestMetadata.fresh(), tokens::add, result -> done.incrementAndGet(), errors::add);

        assertThat(done.get()).isZero();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        assertThat(tokens).isEmpty();
        verify(kilo, never()).completeStream(any(), any(), any(), any(), any());
    }

    @Test void enabledKiloPresetKeepsWorkingForSyncAndStreaming() {
        var user = UUID.randomUUID();
        var enabled = kilo(true);
        var service = mock(UserAiProviderService.class);
        when(service.resolve(user, ModelPurpose.KNOWLEDGE_CHAT)).thenReturn(enabled);
        var kilo = kiloExecution(enabled);
        ChatCompletionResult expected = new ChatCompletionResult("ok", "OPENAI_COMPATIBLE", FREE_MODEL, 1, 1, 5L);
        when(kilo.complete(any(), any())).thenReturn(expected);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<ChatCompletionResult> onDone = invocation.getArgument(3);
            onDone.accept(expected);
            return null;
        }).when(kilo).completeStream(any(), any(), any(), any(), any());

        var gateway = gateway(service, kilo);
        var command = command(user, ModelPurpose.KNOWLEDGE_CHAT);
        assertThat(gateway.complete(command, AiRequestMetadata.fresh())).isEqualTo(expected);

        AtomicInteger done = new AtomicInteger();
        List<Exception> errors = new ArrayList<>();
        gateway.completeStream(command, AiRequestMetadata.fresh(),
                token -> { }, result -> done.incrementAndGet(), errors::add);

        assertThat(done.get()).isEqualTo(1);
        assertThat(errors).isEmpty();

        ArgumentCaptor<UserAiProvider> provider = ArgumentCaptor.forClass(UserAiProvider.class);
        verify(kilo).complete(provider.capture(), any());
        assertThat(provider.getValue().enabled()).isTrue();
        verify(kilo).completeStream(any(), any(), any(), any(), any());
    }
}
