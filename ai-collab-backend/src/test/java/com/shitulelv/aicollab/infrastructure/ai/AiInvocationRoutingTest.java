package com.shitulelv.aicollab.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderAdapter;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.model.RoutingChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiInvocationRoutingTest {

    private UserAiProvider providerOf(UUID user) {
        OffsetDateTime now = OffsetDateTime.now();
        return new UserAiProvider(UUID.randomUUID(), user, "main",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.openai.com", "/v1/chat/completions",
                "enc", "gpt-4o-mini", true, 0.2, 1200, EnumSet.of(ModelCapability.CHAT),
                true, now, now);
    }

    private RoutingChatModelGateway gateway(UserAiProviderService providers,
            ModelSecretCipher secrets, ModelProviderAdapter adapter) {
        return new RoutingChatModelGateway(providers, secrets, List.of(adapter), new ObjectMapper());
    }

    private ModelProviderAdapter adapter(ChatCompletionResult result) {
        ModelProviderAdapter adapter = mock(ModelProviderAdapter.class);
        when(adapter.providerType()).thenReturn(ModelProviderType.OPENAI_COMPATIBLE);
        when(adapter.complete(any(ModelConfiguration.class), any(), any(ChatCompletionCommand.class)))
                .thenReturn(result);
        return adapter;
    }

    private ChatCompletionCommand command(UUID projectId, UUID caller, ModelPurpose purpose) {
        return new ChatCompletionCommand(projectId, "system", "user",
                ChatCompletionCommand.OutputFormat.TEXT, purpose, null, List.of(), caller);
    }

    private ChatCompletionResult result() {
        return new ChatCompletionResult("ok", "openai", "gpt-4o-mini", 1, 1, 5L);
    }

    @Test
    void knowledge_uses_caller_model() {
        UUID caller = UUID.randomUUID();
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.resolve(caller, ModelPurpose.KNOWLEDGE_CHAT)).thenReturn(providerOf(caller));
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        when(secrets.decrypt("enc")).thenReturn("key");

        ChatCompletionResult done = gateway(providers, secrets, adapter(result()))
                .complete(command(UUID.randomUUID(), caller, ModelPurpose.KNOWLEDGE_CHAT));

        assertThat(done.content()).isEqualTo("ok");
        verify(providers).resolve(caller, ModelPurpose.KNOWLEDGE_CHAT);
    }

    @Test
    void planning_uses_actor_model() {
        UUID actor = UUID.randomUUID();
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.resolve(actor, ModelPurpose.PLANNING)).thenReturn(providerOf(actor));
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        when(secrets.decrypt("enc")).thenReturn("key");

        gateway(providers, secrets, adapter(result()))
                .complete(command(UUID.randomUUID(), actor, ModelPurpose.PLANNING));

        verify(providers).resolve(actor, ModelPurpose.PLANNING);
    }

    @Test
    void agent_uses_requester_model() {
        UUID requester = UUID.randomUUID();
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.resolve(requester, ModelPurpose.AGENT)).thenReturn(providerOf(requester));
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        when(secrets.decrypt("enc")).thenReturn("key");

        gateway(providers, secrets, adapter(result()))
                .complete(command(UUID.randomUUID(), requester, ModelPurpose.AGENT));

        verify(providers).resolve(requester, ModelPurpose.AGENT);
    }

    @Test
    void missing_caller_is_unavailable() {
        UserAiProviderService providers = mock(UserAiProviderService.class);
        RoutingChatModelGateway gateway = gateway(providers, mock(ModelSecretCipher.class),
                adapter(result()));
        ChatCompletionCommand command =
                new ChatCompletionCommand(UUID.randomUUID(), "system", "user");

        assertThatThrownBy(() -> gateway.complete(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
    }
}
