package com.shitulelv.aicollab.infrastructure.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingChatModelGatewayTest {

    @Test
    void usesPlanningSpecificFallbackWhenNoAdminModelIsAssigned() {
        ModelConfigurationRepository configurations = mock(ModelConfigurationRepository.class);
        ModelSecretCipher secrets = mock(ModelSecretCipher.class);
        ChatModelGateway chatFallback = mock(ChatModelGateway.class);
        ChatModelGateway planningFallback = mock(ChatModelGateway.class);
        ChatCompletionCommand command = new ChatCompletionCommand(
                "system",
                "user",
                ChatCompletionCommand.OutputFormat.JSON_OBJECT,
                ModelPurpose.PLANNING,
                null,
                List.of());
        ChatCompletionResult expected = new ChatCompletionResult(
                "{}",
                "provider",
                "planning-model",
                null,
                null,
                1);
        when(configurations.findAssigned(ModelPurpose.PLANNING)).thenReturn(java.util.Optional.empty());
        when(planningFallback.complete(command)).thenReturn(expected);
        RoutingChatModelGateway gateway = new RoutingChatModelGateway(
                configurations,
                secrets,
                List.of(),
                chatFallback,
                planningFallback,
                new ObjectMapper());

        assertThat(gateway.complete(command)).isSameAs(expected);
        verify(planningFallback).complete(command);
        verifyNoInteractions(chatFallback);
    }
}
