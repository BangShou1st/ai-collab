package com.shitulelv.aicollab.infrastructure.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutingChatModelGatewayTest {

    @Test
    void throwsWhenCallerHasNoModelConfigured() {
        com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService providers =
                mock(com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService.class);
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        UUID caller = UUID.randomUUID();
        ChatCompletionCommand command = new ChatCompletionCommand(
                UUID.randomUUID(), "system", "user",
                ChatCompletionCommand.OutputFormat.TEXT,
                ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), caller);
        when(providers.resolve(caller, ModelPurpose.KNOWLEDGE_CHAT))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));

        RoutingChatModelGateway gateway = new RoutingChatModelGateway(
                providers, secrets, List.of(), new ObjectMapper());

        assertThatThrownBy(() -> gateway.complete(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
    }

    @Test
    void throwsWhenCallerIsNull() {
        com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService providers =
                mock(com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService.class);
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        ChatCompletionCommand command = new ChatCompletionCommand(
                UUID.randomUUID(), "system", "user");

        RoutingChatModelGateway gateway = new RoutingChatModelGateway(
                providers, secrets, List.of(), new ObjectMapper());

        assertThatThrownBy(() -> gateway.complete(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
    }
}
