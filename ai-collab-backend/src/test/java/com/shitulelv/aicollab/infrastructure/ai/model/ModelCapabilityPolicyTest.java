package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCapabilityPolicyTest {
    @Test
    void rejectsStreamingWhenAdministratorDidNotDeclareStreamingCapability() {
        OffsetDateTime now = OffsetDateTime.now();
        ModelConfiguration configuration = new ModelConfiguration(
                UUID.randomUUID(), "chat", ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.com", "/v1/chat/completions", "secret", "model", true,
                0.2, 1000, EnumSet.of(ModelCapability.CHAT), now, now);

        assertThatThrownBy(() -> ModelCapabilityPolicy.require(
                configuration,
                new ChatCompletionCommand("system", "user"),
                true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("STREAMING");
    }
}
