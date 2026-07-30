package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelProviderAdapterContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode schema = mapper.createObjectNode()
            .put("type", "object")
            .set("properties", mapper.createObjectNode()
                    .set("title", mapper.createObjectNode().put("type", "string")));
    private final ChatCompletionCommand command = new ChatCompletionCommand(
            "system", "user", ChatCompletionCommand.OutputFormat.JSON_OBJECT,
            ModelPurpose.AGENT, schema,
            List.of(new ModelToolDefinition("create_task", "Create a task", schema)));

    @Test
    void openAiCompatibleUsesChatCompletionsContract() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.put("model", "test-model");
        response.putArray("choices").addObject()
                .put("finish_reason", "stop").putObject("message").put("content", "{}");
        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        new OpenAiCompatibleModelAdapter(mapper, http)
                .complete(config(ModelProviderType.OPENAI_COMPATIBLE, "/v1/chat/completions"), "key", command);

        JsonNode body = capturedBody(http);
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("tools").path(0).path("function").path("parameters")).isEqualTo(schema);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
    }

    @Test
    void anthropicUsesMessagesAndInputSchemaContract() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.put("model", "claude-test").put("stop_reason", "end_turn");
        response.putArray("content").addObject().put("type", "text").put("text", "{}");
        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        new AnthropicModelAdapter(mapper, http)
                .complete(config(ModelProviderType.ANTHROPIC, "/v1/messages"), "key", command);

        JsonNode body = capturedBody(http);
        assertThat(body.path("system").asText()).isEqualTo("system");
        assertThat(body.path("tools").path(0).path("input_schema")).isEqualTo(schema);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void geminiUsesGenerateContentAndFunctionDeclarationsContract() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.putArray("candidates").addObject().put("finishReason", "STOP")
                .putObject("content").putArray("parts").addObject().put("text", "{}");
        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        new GeminiModelAdapter(mapper, http)
                .complete(config(ModelProviderType.GEMINI, "/v1beta/models/{model}:generateContent"),
                        "key", command);

        JsonNode body = capturedBody(http);
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asText())
                .isEqualTo("system");
        assertThat(body.path("generationConfig").path("responseJsonSchema")).isEqualTo(schema);
        assertThat(body.path("tools").path(0).path("functionDeclarations").path(0)
                .path("parameters")).isEqualTo(schema);
    }

    @Test
    void encryptsWithRandomIvAndDecryptsBothValues() {
        ModelSecretCipher cipher = new ModelSecretCipher("0123456789abcdef");
        String first = cipher.encrypt("secret");
        String second = cipher.encrypt("secret");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("secret");
        assertThat(cipher.decrypt(second)).isEqualTo("secret");
    }

    private ModelConfiguration config(ModelProviderType type, String path) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                UUID.randomUUID(), "test", type, "https://example.com", path,
                "encrypted", "test-model", true, 0.2, 1000,
                EnumSet.allOf(ModelCapability.class), now, now);
    }

    private static JsonNode capturedBody(JsonHttpModelClient http) {
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        return body.getValue();
    }
}
