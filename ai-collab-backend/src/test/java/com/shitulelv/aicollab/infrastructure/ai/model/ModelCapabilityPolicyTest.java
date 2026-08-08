package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCapabilityPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsStreamingWhenAdministratorDidNotDeclareStreamingCapability() {
        OffsetDateTime now = OffsetDateTime.now();
        ModelConfiguration configuration = new ModelConfiguration(
                UUID.randomUUID(), UUID.randomUUID(), "chat", ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.com", "/v1/chat/completions", "secret", "model", true,
                0.2, 1000, EnumSet.of(ModelCapability.CHAT), now, now);

        assertThatThrownBy(() -> ModelCapabilityPolicy.require(
                configuration,
                new ChatCompletionCommand("system", "user"),
                true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("STREAMING");
    }

    @Test
    void rejectsNativeToolsWhenNoNativeToolsCapability() {
        OffsetDateTime now = OffsetDateTime.now();
        ModelConfiguration configuration = new ModelConfiguration(
                UUID.randomUUID(), UUID.randomUUID(), "chat", ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.com", "/v1/chat/completions", "secret", "model", true,
                0.2, 1000, EnumSet.of(ModelCapability.CHAT), now, now);

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ModelTurnCommand command = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.User("hi")),
                List.of(new ModelToolDefinition("t", "d", schema)),
                false);

        assertThatThrownBy(() -> ModelCapabilityPolicy.require(configuration, command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("NATIVE_TOOLS");
    }

    @Test
    void rejectsChatWhenNoChatCapability() {
        OffsetDateTime now = OffsetDateTime.now();
        ModelConfiguration configuration = new ModelConfiguration(
                UUID.randomUUID(), UUID.randomUUID(), "chat", ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.com", "/v1/chat/completions", "secret", "model", true,
                0.2, 1000, EnumSet.of(ModelCapability.NATIVE_TOOLS), now, now);

        ModelTurnCommand command = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.User("hi")),
                List.of(),
                false);

        assertThatThrownBy(() -> ModelCapabilityPolicy.require(configuration, command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CHAT");
    }

    @Test
    void acceptsModelWithBothChatAndNativeTools() {
        OffsetDateTime now = OffsetDateTime.now();
        ModelConfiguration configuration = new ModelConfiguration(
                UUID.randomUUID(), UUID.randomUUID(), "chat", ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.com", "/v1/chat/completions", "secret", "model", true,
                0.2, 1000, EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS),
                now, now);

        ModelTurnCommand command = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.User("hi")),
                List.of(),
                false);

        // 不应抛出异常
        ModelCapabilityPolicy.require(configuration, command);
    }
}
