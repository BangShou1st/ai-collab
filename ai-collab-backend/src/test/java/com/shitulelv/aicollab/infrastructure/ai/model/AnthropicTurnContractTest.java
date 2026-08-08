package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Anthropic 原生 Tool Calling 契约测试。
 * 覆盖：tools 请求、text block、tool_use block、多 tool_use、
 * text + tool_use、tool_result 回传、is_error、stop_reason、
 * usage、普通文本接口不回归。
 */
class AnthropicTurnContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode schema = mapper.createObjectNode()
            .put("type", "object")
            .set("properties", mapper.createObjectNode()
                    .put("title", mapper.createObjectNode().put("type", "string")));

    // ========== 响应解析 ==========

    @Test
    void parseTextOnly() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("end_turn");
        ArrayNode content = response.putArray("content");
        content.addObject().put("type", "text").put("text", "最终回答");
        response.putObject("usage").put("input_tokens", 100).put("output_tokens", 50);

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.content()).isEqualTo("最终回答");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(result.provider()).isEqualTo("ANTHROPIC");
        assertThat(result.usage().inputTokens()).isEqualTo(100);
        assertThat(result.usage().outputTokens()).isEqualTo(50);
    }

    @Test
    void parseToolUseBlock() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("tool_use");
        ArrayNode content = response.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use").put("id", "toolu_123").put("name", "list_tasks");
        toolUse.set("input", mapper.createObjectNode().put("status", "OPEN"));

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().getFirst().id()).isEqualTo("toolu_123");
        assertThat(result.toolCalls().getFirst().name()).isEqualTo("list_tasks");
        assertThat(result.toolCalls().getFirst().arguments().path("status").asText())
                .isEqualTo("OPEN");
        assertThat(result.content()).isEmpty();
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
    }

    @Test
    void parseMultipleToolUse() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("tool_use");
        ArrayNode content = response.putArray("content");
        ObjectNode tu1 = content.addObject();
        tu1.put("type", "tool_use").put("id", "toolu_1").put("name", "list_tasks");
        tu1.set("input", mapper.createObjectNode());
        ObjectNode tu2 = content.addObject();
        tu2.put("type", "tool_use").put("id", "toolu_2").put("name", "list_milestones");
        tu2.set("input", mapper.createObjectNode());

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", multiToolCommand());

        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("list_tasks");
        assertThat(result.toolCalls().get(1).name()).isEqualTo("list_milestones");
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
    }

    @Test
    void textAndToolUseCoexist() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("tool_use");
        ArrayNode content = response.putArray("content");
        content.addObject().put("type", "text").put("text", "让我先查看一下");
        ObjectNode tu = content.addObject();
        tu.put("type", "tool_use").put("id", "toolu_1").put("name", "list_tasks");
        tu.set("input", mapper.createObjectNode());

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result.content()).isEqualTo("让我先查看一下");
        assertThat(result.toolCalls()).hasSize(1);
    }

    @Test
    void emptyResponseThrows() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("end_turn");
        response.putArray("content");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        assertThatThrownBy(() -> new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void maxTokensThrows() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("max_tokens");
        response.putArray("content").addObject().put("type", "text").put("text", "truncated");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        assertThatThrownBy(() -> new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));
    }

    // ========== 请求映射 ==========

    @Test
    void sendsToolDefinitions() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createResponseWithText("end_turn", "ok"));

        new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        JsonNode body = capturedBody(http);
        assertThat(body.path("tools").isArray()).isTrue();
        assertThat(body.path("tools").path(0).path("name").asText()).isEqualTo("list_tasks");
        assertThat(body.path("tools").path(0).path("input_schema")).isEqualTo(schema);
    }

    @Test
    void sendsSystemAsTopLevelField() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createResponseWithText("end_turn", "ok"));

        new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        JsonNode body = capturedBody(http);
        assertThat(body.path("system").asText()).isEqualTo("sys");
    }

    @Test
    void sendsToolResultAsUserMessage() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createResponseWithText("end_turn", "ok"));

        ObjectNode args = mapper.createObjectNode().put("status", "OPEN");
        ModelToolCall tc = new ModelToolCall("toolu_1", "list_tasks", args);
        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("查任务"),
                        new ModelMessage.Assistant("好的", List.of(tc)),
                        new ModelMessage.ToolResult("toolu_1", "list_tasks",
                                mapper.createObjectNode().put("count", 3), false)),
                List.of(),
                false);

        new AnthropicModelAdapter(mapper, http).turn(config(), "key", cmd);

        JsonNode body = capturedBody(http);
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(3); // user, assistant, user(tool_result)
        assertThat(messages.path(1).path("role").asText()).isEqualTo("assistant");
        // assistant message has text first, then tool_use
        assertThat(messages.path(1).path("content").path(0).path("type").asText())
                .isEqualTo("text");
        assertThat(messages.path(1).path("content").path(1).path("type").asText())
                .isEqualTo("tool_use");
        assertThat(messages.path(2).path("role").asText()).isEqualTo("user");
        assertThat(messages.path(2).path("content").path(0).path("type").asText())
                .isEqualTo("tool_result");
        assertThat(messages.path(2).path("content").path(0).path("tool_use_id").asText())
                .isEqualTo("toolu_1");
    }

    @Test
    void sendsToolResultWithErrorFlag() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createResponseWithText("end_turn", "ok"));

        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("查任务"),
                        new ModelMessage.Assistant("调用工具", List.of(
                                new ModelToolCall("toolu_1", "list_tasks", mapper.createObjectNode()))),
                        new ModelMessage.ToolResult("toolu_1", "list_tasks",
                                mapper.createObjectNode().put("error", "工具执行失败"), true)),
                List.of(),
                false);

        new AnthropicModelAdapter(mapper, http).turn(config(), "key", cmd);

        JsonNode body = capturedBody(http);
        JsonNode toolResult = body.path("messages").path(2).path("content").path(0);
        assertThat(toolResult.path("is_error").asBoolean()).isTrue();
    }

    @Test
    void parsesUsage() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("end_turn");
        response.putArray("content").addObject().put("type", "text").put("text", "ok");
        response.putObject("usage").put("input_tokens", 200).put("output_tokens", 100);

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().inputTokens()).isEqualTo(200);
        assertThat(result.usage().outputTokens()).isEqualTo(100);
    }

    @Test
    void nullUsageIsAllowed() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("end_turn");
        response.putArray("content").addObject().put("type", "text").put("text", "ok");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new AnthropicModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.usage()).isNull();
    }

    // ========== 旧接口不回归 ==========

    @Test
    void legacyCompleteStillWorks() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("end_turn");
        response.putArray("content").addObject().put("type", "text").put("text", "legacy answer");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ChatCompletionResult result = new AnthropicModelAdapter(mapper, http)
                .complete(config(), "key", legacyCommand());

        assertThat(result.content()).isEqualTo("legacy answer");
    }

    // ========== 辅助方法 ==========

    private ObjectNode createResponse(String stopReason) {
        ObjectNode response = mapper.createObjectNode();
        response.put("model", "claude-test");
        response.put("stop_reason", stopReason);
        return response;
    }

    private ObjectNode createResponseWithText(String stopReason, String text) {
        ObjectNode response = createResponse(stopReason);
        response.putArray("content").addObject().put("type", "text").put("text", text);
        return response;
    }

    private ModelConfiguration config() {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                UUID.randomUUID(), UUID.randomUUID(), "test", ModelProviderType.ANTHROPIC,
                "https://example.com", "/v1/messages",
                "encrypted", "claude-test", true, 0.2, 1000,
                EnumSet.allOf(ModelCapability.class), now, now);
    }

    private ModelTurnCommand singleToolCommand() {
        return new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.System("sys"), new ModelMessage.User("查任务")),
                List.of(new ModelToolDefinition("list_tasks", "列出任务", schema)),
                false);
    }

    private ModelTurnCommand multiToolCommand() {
        return new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.System("sys"), new ModelMessage.User("查任务和里程碑")),
                List.of(
                        new ModelToolDefinition("list_tasks", "列出任务", schema),
                        new ModelToolDefinition("list_milestones", "列出里程碑", schema)),
                false);
    }

    private ModelTurnCommand textOnlyCommand() {
        return new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.System("sys"), new ModelMessage.User("你好")),
                List.of(),
                false);
    }

    private ChatCompletionCommand legacyCommand() {
        return new ChatCompletionCommand(
                null, "system", "user", ChatCompletionCommand.OutputFormat.TEXT,
                ModelPurpose.AGENT, null, List.of());
    }

    private static JsonNode capturedBody(JsonHttpModelClient http) {
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        return body.getValue();
    }
}
