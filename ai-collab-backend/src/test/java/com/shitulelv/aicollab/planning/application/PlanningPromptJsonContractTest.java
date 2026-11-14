package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.JsonHttpModelClient;
import com.shitulelv.aicollab.infrastructure.ai.model.OpenAiCompatibleModelAdapter;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规划出站契约：提示词 JSON 不携带原生 JSON 模式，通用 JSON_OBJECT 仍可按 schema 下发。
 */
class PlanningPromptJsonContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void planningPromptJsonCommandSendsNoNativeJsonModeOnTheWire() throws Exception {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenReturn(mapper.readTree(
                "{\"model\":\"planning-model\",\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}"));
        OpenAiCompatibleModelAdapter adapter = new OpenAiCompatibleModelAdapter(mapper, http);

        ChatCompletionCommand command = new ChatCompletionCommand(null, "system",
                "只输出合法 JSON 对象，不要输出其他文字", ChatCompletionCommand.OutputFormat.PROMPT_JSON,
                ModelPurpose.PLANNING, null, List.of(), UUID.randomUUID());
        ChatCompletionResult result = adapter.complete(config(), "test-key", command);

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        JsonNode sent = body.getValue();
        assertThat(sent.has("response_format")).isFalse();
        assertThat(sent.path("messages").get(1).path("content").asText()).contains("只输出合法 JSON 对象");
    }

    @Test
    void genericJsonObjectStillCarriesSchemaOnTheWire() throws Exception {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenReturn(mapper.readTree(
                "{\"model\":\"planning-model\",\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}"));
        OpenAiCompatibleModelAdapter adapter = new OpenAiCompatibleModelAdapter(mapper, http);
        var schema = mapper.readTree("""
                {"type":"object","required":["tasks"],"properties":{"tasks":{"type":"array","items":{"type":"string"}}},
                 "additionalProperties":false}
                """);

        adapter.complete(config(), "test-key", new ChatCompletionCommand(null, "system", "user",
                ChatCompletionCommand.OutputFormat.JSON_OBJECT,
                ModelPurpose.PLANNING, schema, List.of(), UUID.randomUUID()));

        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        assertThat(body.getValue().path("response_format").path("type").asText()).isEqualTo("json_schema");
    }

    private ModelConfiguration config() {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "planning",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com", "/v1/chat/completions",
                "enc", "planning-model", true, 0.2, 6000,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT), now, now);
    }
}
