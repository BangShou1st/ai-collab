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
 * Gemini 原生 Tool Calling 契约测试。
 * 覆盖：functionDeclarations、text part、functionCall、
 * 多 functionCall、text + functionCall、functionResponse 回传、
 * 内部 Tool Call ID 稳定、finishReason、usageMetadata、
 * 普通文本接口不回归。
 */
class GeminiTurnContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode schema = mapper.createObjectNode()
            .put("type", "object")
            .set("properties", mapper.createObjectNode()
                    .put("title", mapper.createObjectNode().put("type", "string")));

    // ========== 响应解析 ==========

    @Test
    void parseTextOnly() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createTextResponse("STOP", "最终回答");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.content()).isEqualTo("最终回答");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(result.provider()).isEqualTo("GEMINI");
    }

    @Test
    void parseFunctionCall() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createFunctionCallResponse("STOP", "list_tasks",
                mapper.createObjectNode().put("status", "OPEN"));

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().getFirst().name()).isEqualTo("list_tasks");
        assertThat(result.toolCalls().getFirst().arguments().path("status").asText())
                .isEqualTo("OPEN");
        assertThat(result.toolCalls().getFirst().id()).startsWith("gemini-call-");
        assertThat(result.content()).isEmpty();
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.STOP);
    }

    @Test
    void parseMultipleFunctionCalls() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-test");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        ObjectNode content = candidate.putObject("content").put("role", "model");
        ArrayNode parts = content.putArray("parts");
        ObjectNode fc1 = parts.addObject().putObject("functionCall");
        fc1.put("name", "list_tasks").set("args", mapper.createObjectNode());
        ObjectNode fc2 = parts.addObject().putObject("functionCall");
        fc2.put("name", "list_milestones").set("args", mapper.createObjectNode());

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", multiToolCommand());

        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("list_tasks");
        assertThat(result.toolCalls().get(1).name()).isEqualTo("list_milestones");
        // 内部 ID 稳定且不同
        assertThat(result.toolCalls().get(0).id()).isNotEqualTo(result.toolCalls().get(1).id());
    }

    @Test
    void textAndFunctionCallCoexist() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-test");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        ObjectNode content = candidate.putObject("content").put("role", "model");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", "让我先查看一下");
        ObjectNode fc = parts.addObject().putObject("functionCall");
        fc.put("name", "list_tasks").set("args", mapper.createObjectNode());

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result.content()).isEqualTo("让我先查看一下");
        assertThat(result.toolCalls()).hasSize(1);
    }

    @Test
    void emptyResponseThrows() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createTextResponse("STOP", "");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        assertThatThrownBy(() -> new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void maxTokensThrows() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createTextResponse("MAX_TOKENS", "truncated");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        assertThatThrownBy(() -> new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));
    }

    // ========== 请求映射 ==========

    @Test
    void sendsFunctionDeclarations() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createTextResponse("STOP", "ok"));

        new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        JsonNode body = capturedBody(http);
        assertThat(body.path("tools").path(0).path("functionDeclarations").isArray()).isTrue();
        assertThat(body.path("tools").path(0).path("functionDeclarations")
                .path(0).path("name").asText()).isEqualTo("list_tasks");
    }

    @Test
    void sendsSystemInstruction() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createTextResponse("STOP", "ok"));

        new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        JsonNode body = capturedBody(http);
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asText())
                .isEqualTo("sys");
    }

    @Test
    void sendsFunctionResponse() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createTextResponse("STOP", "ok"));

        ObjectNode args = mapper.createObjectNode().put("status", "OPEN");
        ModelToolCall tc = new ModelToolCall("gemini-call-0", "list_tasks", args);
        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("查任务"),
                        new ModelMessage.Assistant("好的", List.of(tc)),
                        new ModelMessage.ToolResult("gemini-call-0", "list_tasks",
                                mapper.createObjectNode().put("count", 3), false)),
                List.of(),
                false);

        new GeminiModelAdapter(mapper, http).turn(config(), "key", cmd);

        JsonNode body = capturedBody(http);
        JsonNode contents = body.path("contents");
        assertThat(contents).hasSize(3); // user, model, user(functionResponse)
        assertThat(contents.path(2).path("role").asText()).isEqualTo("user");
        assertThat(contents.path(2).path("parts").path(0)
                .path("functionResponse").path("name").asText()).isEqualTo("list_tasks");
    }

    @Test
    void parsesUsageMetadata() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createTextResponse("STOP", "ok");
        ObjectNode usage = response.putObject("usageMetadata");
        usage.put("promptTokenCount", 150).put("candidatesTokenCount", 75);

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().inputTokens()).isEqualTo(150);
        assertThat(result.usage().outputTokens()).isEqualTo(75);
    }

    @Test
    void nullUsageIsAllowed() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createTextResponse("STOP", "ok");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.usage()).isNull();
    }

    @Test
    void internalToolCallIdIsStable() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createFunctionCallResponse("STOP", "list_tasks", mapper.createObjectNode());

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        // ID 应该是 gemini-call-0
        assertThat(result.toolCalls().getFirst().id()).isEqualTo("gemini-call-0");
    }

    @Test
    void toolCallIdsUniqueAcrossTurns() {
        // 模拟两轮模型调用：第二轮消息包含第一轮的 functionCall，
        // 第二轮新的 functionCall 应获得不同的 ID
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createFunctionCallResponse("STOP", "list_milestones", mapper.createObjectNode());

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        // 第二轮命令：包含第一轮 assistant 的 functionCall 和对应的 functionResponse
        ObjectNode firstCallArgs = mapper.createObjectNode().put("status", "OPEN");
        ModelToolCall firstCall = new ModelToolCall("gemini-call-0", "list_tasks", firstCallArgs);
        ModelTurnCommand secondTurnCmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("查任务"),
                        new ModelMessage.Assistant("我来查看", List.of(firstCall)),
                        new ModelMessage.ToolResult("gemini-call-0", "list_tasks",
                                mapper.createObjectNode().put("count", 3), false)),
                List.of(
                        new ModelToolDefinition("list_tasks", "列出任务", schema),
                        new ModelToolDefinition("list_milestones", "列出里程碑", schema)),
                false);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", secondTurnCmd);

        // 第二轮的 ID 应该偏移已有 1 个 functionCall，即 gemini-call-1
        assertThat(result.toolCalls().getFirst().id()).isEqualTo("gemini-call-1");
        assertThat(result.toolCalls().getFirst().id())
                .isNotEqualTo("gemini-call-0");
    }

    // ========== 旧接口不回归 ==========

    @Test
    void legacyCompleteStillWorks() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createTextResponse("STOP", "legacy answer");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ChatCompletionResult result = new GeminiModelAdapter(mapper, http)
                .complete(config(), "key", legacyCommand());

        assertThat(result.content()).isEqualTo("legacy answer");
    }

    // ========== 辅助方法 ==========

    private ObjectNode createTextResponse(String finishReason, String text) {
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-test");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", finishReason);
        ObjectNode content = candidate.putObject("content").put("role", "model");
        content.putArray("parts").addObject().put("text", text);
        return response;
    }

    private ObjectNode createFunctionCallResponse(
            String finishReason, String funcName, JsonNode args) {
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-test");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", finishReason);
        ObjectNode content = candidate.putObject("content").put("role", "model");
        ObjectNode fc = content.putArray("parts").addObject().putObject("functionCall");
        fc.put("name", funcName);
        fc.set("args", args);
        return response;
    }

    private ModelConfiguration config() {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                UUID.randomUUID(), UUID.randomUUID(), "test", ModelProviderType.GEMINI,
                "https://example.com", "/v1beta/models/{model}:generateContent",
                "encrypted", "gemini-test", true, 0.2, 1000,
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
