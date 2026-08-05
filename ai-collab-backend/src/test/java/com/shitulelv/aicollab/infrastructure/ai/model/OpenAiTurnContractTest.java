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
 * OpenAI-compatible 原生 Tool Calling 契约测试。
 * 覆盖：发送 tools、解析单/多 tool_calls、文本和 tool_calls 并存、
 * 非法 arguments JSON、assistant tool_calls 回传、tool result 回传、
 * finish_reason 映射、usage 解析、普通文本接口不回归。
 */
class OpenAiTurnContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode schema = mapper.createObjectNode()
            .put("type", "object")
            .set("properties", mapper.createObjectNode()
                    .set("title", mapper.createObjectNode().put("type", "string")));

    // ========== 响应解析 ==========

    @Test
    void parseSingleToolCall() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponseWithNullContent("stop");
        ObjectNode message = getMessage(response);
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("id", "call_123").put("type", "function");
        ObjectNode fn = tc.putObject("function");
        fn.put("name", "list_tasks").put("arguments", "{\"status\":\"OPEN\"}");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().getFirst().id()).isEqualTo("call_123");
        assertThat(result.toolCalls().getFirst().name()).isEqualTo("list_tasks");
        assertThat(result.toolCalls().getFirst().arguments().path("status").asText()).isEqualTo("OPEN");
        assertThat(result.content()).isEmpty();
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(result.provider()).isEqualTo("OPENAI_COMPATIBLE");
    }

    @Test
    void parseMultipleToolCalls() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("tool_calls");
        ObjectNode message = getMessage(response);
        message.put("content", "我来查看任务和里程碑");
        ArrayNode toolCalls = message.putArray("tool_calls");

        ObjectNode tc1 = toolCalls.addObject();
        tc1.put("id", "call_1").put("type", "function");
        tc1.putObject("function").put("name", "list_tasks").put("arguments", "{}");

        ObjectNode tc2 = toolCalls.addObject();
        tc2.put("id", "call_2").put("type", "function");
        tc2.putObject("function").put("name", "list_milestones").put("arguments", "{}");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", multiToolCommand());

        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("list_tasks");
        assertThat(result.toolCalls().get(1).name()).isEqualTo("list_milestones");
        assertThat(result.content()).isEqualTo("我来查看任务和里程碑");
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
    }

    @Test
    void textAndToolCallsCoexist() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("tool_calls");
        ObjectNode message = getMessage(response);
        message.put("content", "让我先查看一下");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("id", "call_1").put("type", "function");
        tc.putObject("function").put("name", "list_tasks").put("arguments", "{}");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result.content()).isEqualTo("让我先查看一下");
        assertThat(result.toolCalls()).hasSize(1);
    }

    @Test
    void textOnlyResponse() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("stop");
        ObjectNode message = getMessage(response);
        message.put("content", "最终回答");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.content()).isEqualTo("最终回答");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.STOP);
    }

    @Test
    void invalidArgumentsJsonThrows() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponseWithNullContent("tool_calls");
        ObjectNode message = getMessage(response);
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("id", "call_1").put("type", "function");
        tc.putObject("function").put("name", "list_tasks").put("arguments", "not-json");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        assertThatThrownBy(() -> new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE));
    }

    @Test
    void emptyResponseThrows() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponseWithNullContent("stop");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        assertThatThrownBy(() -> new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void finishReasonLengthThrows() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("length");
        ObjectNode message = getMessage(response);
        message.put("content", "truncated");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        assertThatThrownBy(() -> new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));
    }

    // ========== 请求映射 ==========

    @Test
    void sendsToolDefinitions() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createResponse("stop"));

        new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        JsonNode body = capturedBody(http);
        assertThat(body.path("tools").isArray()).isTrue();
        assertThat(body.path("tools").path(0).path("function").path("name").asText())
                .isEqualTo("list_tasks");
        assertThat(body.path("tool_choice").asText()).isEqualTo("auto");
    }

    @Test
    void sendsToolChoiceRequiredWhenToolsRequired() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createResponse("stop"));

        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.User("hi")),
                List.of(new ModelToolDefinition("t", "d", schema)),
                true);
        new OpenAiCompatibleModelAdapter(mapper, http).turn(config(), "key", cmd);

        JsonNode body = capturedBody(http);
        assertThat(body.path("tool_choice").asText()).isEqualTo("required");
    }

    @Test
    void sendsAssistantToolCallsInRequest() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createResponse("stop"));

        ObjectNode args = mapper.createObjectNode().put("status", "OPEN");
        ModelToolCall tc = new ModelToolCall("call_1", "list_tasks", args);
        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("查任务"),
                        new ModelMessage.Assistant("好的", List.of(tc)),
                        new ModelMessage.ToolResult("call_1", "list_tasks",
                                mapper.createObjectNode().put("count", 3), false)),
                List.of(),
                false);

        new OpenAiCompatibleModelAdapter(mapper, http).turn(config(), "key", cmd);

        JsonNode body = capturedBody(http);
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.path(2).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.path(2).path("tool_calls").path(0).path("id").asText())
                .isEqualTo("call_1");
        assertThat(messages.path(3).path("role").asText()).isEqualTo("tool");
        assertThat(messages.path(3).path("tool_call_id").asText()).isEqualTo("call_1");
    }

    @Test
    void parsesUsageFromResponse() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("stop");
        ObjectNode message = getMessage(response);
        message.put("content", "ok");
        ObjectNode usage = response.putObject("usage");
        usage.put("prompt_tokens", 100).put("completion_tokens", 50);

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().inputTokens()).isEqualTo(100);
        assertThat(result.usage().outputTokens()).isEqualTo(50);
    }

    @Test
    void nullUsageIsAllowed() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("stop");
        ObjectNode message = getMessage(response);
        message.put("content", "ok");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.usage()).isNull();
    }

    @Test
    void unknownFinishReasonMapsToUnknown() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("custom_reason");
        ObjectNode message = getMessage(response);
        message.put("content", "ok");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new OpenAiCompatibleModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.UNKNOWN);
    }

    // ========== 旧接口不回归 ==========

    @Test
    void legacyCompleteStillWorks() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createResponse("stop");
        ObjectNode message = getMessage(response);
        message.put("content", "legacy answer");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ChatCompletionResult result = new OpenAiCompatibleModelAdapter(mapper, http)
                .complete(config(), "key", legacyCommand());

        assertThat(result.content()).isEqualTo("legacy answer");
    }

    // ========== 辅助方法 ==========

    private ObjectNode createResponse(String finishReason) {
        ObjectNode response = mapper.createObjectNode();
        response.put("model", "test-model");
        ObjectNode choice = mapper.createObjectNode();
        choice.put("finish_reason", finishReason);
        ObjectNode message = mapper.createObjectNode();
        message.put("content", "ok");
        choice.set("message", message);
        response.putArray("choices").add(choice);
        return response;
    }

    private ObjectNode createResponseWithNullContent(String finishReason) {
        ObjectNode response = mapper.createObjectNode();
        response.put("model", "test-model");
        ObjectNode choice = mapper.createObjectNode();
        choice.put("finish_reason", finishReason);
        ObjectNode message = mapper.createObjectNode();
        message.putNull("content");
        choice.set("message", message);
        response.putArray("choices").add(choice);
        return response;
    }

    private ObjectNode getMessage(ObjectNode response) {
        return (ObjectNode) response.path("choices").path(0).get("message");
    }

    private ModelConfiguration config() {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                UUID.randomUUID(), "test", ModelProviderType.OPENAI_COMPATIBLE,
                "https://example.com", "/v1/chat/completions",
                "encrypted", "test-model", true, 0.2, 1000,
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
                "system", "user", ChatCompletionCommand.OutputFormat.TEXT,
                ModelPurpose.AGENT, null, List.of());
    }

    private static JsonNode capturedBody(JsonHttpModelClient http) {
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        return body.getValue();
    }
}
