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
 * Planning's JSON contract does not require provider-native JSON mode.
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
    void planningGenerateRequestsPromptJsonCandidate() {
        when(gateway.complete(any(), any())).thenReturn(new ChatCompletionResult(
                "{}", "openai", "gpt-4", 10, 20, 100L));

        client.generate("system", "user", "FEATURE", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());

        ArgumentCaptor<ChatCompletionCommand> captor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(gateway).complete(captor.capture(), any());
        assertThat(captor.getValue().outputFormat()).isEqualTo(ChatCompletionCommand.OutputFormat.PROMPT_JSON);
    }

    @Test
    void repairAndDetailShareStableCorrelationId() {
        when(gateway.complete(any(), any())).thenReturn(new ChatCompletionResult(
                "{}", "openai", "gpt-4", 10, 20, 100L));
        UUID correlation = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        client.generate("s", "u", "SKELETON", actor, project, UUID.randomUUID(), correlation);
        client.generate("s", "u", "REPAIR", actor, project, UUID.randomUUID(), correlation);
        client.generate("s", "u", "DETAIL", actor, project, UUID.randomUUID(), correlation);
        ArgumentCaptor<com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata> meta =
                ArgumentCaptor.forClass(com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata.class);
        verify(gateway, times(3)).complete(any(), meta.capture());
        assertThat(meta.getAllValues()).hasSize(3);
        assertThat(meta.getAllValues().stream().map(m -> m.correlationSessionId()).distinct().toList())
                .containsExactly(correlation.toString());
    }

    /**
     * 规划永不向任何上游请求原生 JSON 模式（json_object / json_schema）。
     *
     * <p>免费聚合网关上的模型并不都实现完整 JSON Schema 语法子集：原生请求要么被网关直接拒绝
     * （MiniMax 拒绝 union 类型 {@code "type":["string","null"]}），要么被静默忽略并返回不合规 JSON。
     * 提示词 JSON 路径在实测的免费模型目录上合规率更高，且输出契约由应用侧严格解析保证。
     * 这与 Zen 预设一直采用的策略一致（见 ZenPlanningContractTest）。</p>
     */
    @Test
    void planningNeverRequestsProviderNativeJsonMode() {
        when(gateway.complete(any(), any())).thenReturn(new ChatCompletionResult(
                "{}", "openai", "gpt-4", 10, 20, 100L));

        client.generate("system", "user", "TASK_PLAN_SKELETON", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        ArgumentCaptor<ChatCompletionCommand> captor = ArgumentCaptor.forClass(ChatCompletionCommand.class);
        verify(gateway).complete(captor.capture(), any());
        ChatCompletionCommand sent = captor.getValue();
        assertThat(sent.outputFormat()).isEqualTo(ChatCompletionCommand.OutputFormat.PROMPT_JSON);
        assertThat(sent.outputFormat()).isNotEqualTo(ChatCompletionCommand.OutputFormat.JSON_OBJECT);
        // 原生模式下 outputSchema 会被翻译成 response_format；提示词模式下不得携带 schema。
        assertThat(sent.outputSchema()).isNull();
    }
}
