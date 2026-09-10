package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.AiCallLogWriter;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verify TaskPlanModelClient uses JSON_OBJECT output format for planning requests.
 */
class TaskPlanModelClientTest {

    private TaskPlanModelClient client;
    private ChatModelGateway gateway;
    private AiCallLogWriter logs;

    @BeforeEach
    void setUp() {
        logs = mock(AiCallLogWriter.class);
        PlanningModelProperties planningProps = new PlanningModelProperties(
                true, "openai", "https://api.openai.com", "/v1", "test-key", "gpt-4",
                Duration.ofSeconds(30), Duration.ofSeconds(60), 0.0, 4000,
                5, 10000, 0.5, 10);
        gateway = mock(ChatModelGateway.class);
        client = new TaskPlanModelClient(planningProps, logs, gateway);
    }

    @Test
    void planningGenerateAlwaysRequestsJsonObjectOutput() {
        when(gateway.complete(any(), any())).thenReturn(new ChatCompletionResult(
                "{}", "openai", "gpt-4", 10, 20, 100L));

        client.generate("system", "user", "FEATURE", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());

        ArgumentCaptor<ChatCompletionCommand> captor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(gateway).complete(captor.capture(), any());
        assertThat(captor.getValue().outputFormat()).isEqualTo(ChatCompletionCommand.OutputFormat.JSON_OBJECT);
    }
}
