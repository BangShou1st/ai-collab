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

    @Test
    void parseFunctionCallWithThoughtSignature() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-3.1-flash-lite");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        ObjectNode content = candidate.putObject("content").put("role", "model");
        ObjectNode part = content.putArray("parts").addObject();
        part.putObject("functionCall").put("name", "list_tasks").set("args", mapper.createObjectNode().put("status", "OPEN"));
        part.put("thoughtSignature", "sig-part-camel-123");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result.toolCalls()).hasSize(1);
        ModelToolCall tc = result.toolCalls().getFirst();
        assertThat(tc.name()).isEqualTo("list_tasks");
        assertThat(tc.thoughtSignature()).isEqualTo("sig-part-camel-123");
    }

    @Test
    void parseFunctionCallWithSnakeCaseThoughtSignature() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-3.1-flash-lite");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        ObjectNode content = candidate.putObject("content").put("role", "model");
        ObjectNode part = content.putArray("parts").addObject();
        part.putObject("functionCall").put("name", "list_tasks").set("args", mapper.createObjectNode());
        part.put("thought_signature", "sig-part-snake-456");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().getFirst().thoughtSignature()).isEqualTo("sig-part-snake-456");
    }

    @Test
    void parseFunctionCallWithNestedThoughtSignature() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-3.1-flash-lite");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        ObjectNode content = candidate.putObject("content").put("role", "model");
        ObjectNode part = content.putArray("parts").addObject();
        ObjectNode fc = part.putObject("functionCall");
        fc.put("name", "list_tasks").set("args", mapper.createObjectNode());
        fc.put("thoughtSignature", "sig-fc-nested-789");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().getFirst().thoughtSignature()).isEqualTo("sig-fc-nested-789");
    }

    @Test
    void parseMultipleFunctionCallsWithThoughtSignatures() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-3.1-flash-lite");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        ObjectNode content = candidate.putObject("content").put("role", "model");
        ArrayNode parts = content.putArray("parts");

        ObjectNode p1 = parts.addObject();
        p1.putObject("functionCall").put("name", "list_tasks").set("args", mapper.createObjectNode());
        p1.put("thoughtSignature", "sig-parallel-1");

        ObjectNode p2 = parts.addObject();
        p2.putObject("functionCall").put("name", "list_milestones").set("args", mapper.createObjectNode());
        p2.put("thoughtSignature", "sig-parallel-2");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", multiToolCommand());

        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(0).thoughtSignature()).isEqualTo("sig-parallel-1");
        assertThat(result.toolCalls().get(1).thoughtSignature()).isEqualTo("sig-parallel-2");
    }

    @Test
    void sendsFunctionCallWithThoughtSignatureInNextTurn() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createTextResponse("STOP", "ok"));

        ObjectNode args = mapper.createObjectNode().put("status", "OPEN");
        ModelToolCall tc = new ModelToolCall("gemini-call-0", "list_tasks", args, "sig-echo-back-abc");
        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("查任务"),
                        new ModelMessage.Assistant("", List.of(tc)),
                        new ModelMessage.ToolResult("gemini-call-0", "list_tasks",
                                mapper.createObjectNode().put("count", 3), false)),
                List.of(),
                false);

        new GeminiModelAdapter(mapper, http).turn(config(), "key", cmd);

        JsonNode body = capturedBody(http);
        JsonNode contents = body.path("contents");
        assertThat(contents).hasSize(3); // user, model, user(functionResponse)

        JsonNode modelMsg = contents.path(1);
        assertThat(modelMsg.path("role").asText()).isEqualTo("model");
        JsonNode modelPart0 = modelMsg.path("parts").path(0);
        assertThat(modelPart0.path("functionCall").path("name").asText()).isEqualTo("list_tasks");
        assertThat(modelPart0.path("thoughtSignature").asText()).isEqualTo("sig-echo-back-abc");
    }

    @Test
    void sendsFunctionCallWithoutThoughtSignatureOmitted() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createTextResponse("STOP", "ok"));

        ObjectNode args = mapper.createObjectNode().put("status", "OPEN");
        ModelToolCall tc = new ModelToolCall("gemini-call-0", "list_tasks", args, null);
        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("查任务"),
                        new ModelMessage.Assistant("", List.of(tc)),
                        new ModelMessage.ToolResult("gemini-call-0", "list_tasks",
                                mapper.createObjectNode().put("count", 3), false)),
                List.of(),
                false);

        new GeminiModelAdapter(mapper, http).turn(config(), "key", cmd);

        JsonNode body = capturedBody(http);
        JsonNode modelPart0 = body.path("contents").path(1).path("parts").path(0);
        assertThat(modelPart0.has("thoughtSignature")).isFalse();
    }

    @Test
    void multiTurnSequentialToolCallsPreservesDistinctSignatures() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createTextResponse("STOP", "最终完成"));

        ModelToolCall call1 = new ModelToolCall("gemini-call-0", "search_repo",
                mapper.createObjectNode().put("q", "pom.xml"), "sig-turn-1");
        ModelToolCall call2 = new ModelToolCall("gemini-call-1", "read_file",
                mapper.createObjectNode().put("path", "pom.xml"), "sig-turn-2");

        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("分析项目"),
                        new ModelMessage.Assistant("第一步检索", List.of(call1)),
                        new ModelMessage.ToolResult("gemini-call-0", "search_repo", mapper.createObjectNode().put("found", true), false),
                        new ModelMessage.Assistant("第二步读取", List.of(call2)),
                        new ModelMessage.ToolResult("gemini-call-1", "read_file", mapper.createObjectNode().put("bytes", 100), false)),
                List.of(new ModelToolDefinition("search_repo", "检索", schema),
                        new ModelToolDefinition("read_file", "读取", schema)),
                false);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http).turn(config(), "key", cmd);

        assertThat(result.content()).isEqualTo("最终完成");
        assertThat(result.toolCalls()).isEmpty();

        JsonNode body = capturedBody(http);
        JsonNode contents = body.path("contents");
        assertThat(contents).hasSize(5); // user, model1, tool1, model2, tool2

        // Verify Model Turn 1
        JsonNode model1 = contents.path(1);
        assertThat(model1.path("parts").path(0).path("text").asText()).isEqualTo("第一步检索");
        assertThat(model1.path("parts").path(1).path("thoughtSignature").asText()).isEqualTo("sig-turn-1");

        // Verify Model Turn 2
        JsonNode model2 = contents.path(3);
        assertThat(model2.path("parts").path(0).path("text").asText()).isEqualTo("第二步读取");
        assertThat(model2.path("parts").path(1).path("thoughtSignature").asText()).isEqualTo("sig-turn-2");
    }

    @Test
    void ignoresThoughtBlockTextInContent() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-3.1-flash-lite");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        ObjectNode content = candidate.putObject("content").put("role", "model");
        ArrayNode parts = content.putArray("parts");

        // part 1: reasoning scratchpad (thought: true)
        parts.addObject().put("text", "Let me think carefully about the architecture.").put("thought", true);
        // part 2: user-facing final text
        parts.addObject().put("text", "这是最终的正式回答。");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand());

        assertThat(result.content()).isEqualTo("这是最终的正式回答。");
        assertThat(result.content()).doesNotContain("Let me think carefully");
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

    @Test
    void multiTurnSequentialToolCallsWithThoughtBlockDoesNotDuplicateToolCallId() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);

        // 第一轮：parts[0] 是思考草稿块 (thought: true)，parts[1] 是 functionCall
        ObjectNode turn1Response = mapper.createObjectNode();
        turn1Response.put("modelVersion", "gemini-3.1-flash-lite");
        ObjectNode candidate1 = turn1Response.putArray("candidates").addObject();
        candidate1.put("finishReason", "STOP");
        ObjectNode content1 = candidate1.putObject("content").put("role", "model");
        ArrayNode parts1 = content1.putArray("parts");
        parts1.addObject().put("text", "正在规划检索...").put("thought", true);
        ObjectNode fc1 = parts1.addObject().putObject("functionCall");
        fc1.put("name", "search_repo").set("args", mapper.createObjectNode().put("q", "pom.xml"));

        when(http.post(anyString(), anyMap(), any())).thenReturn(turn1Response);

        ModelTurnResult result1 = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", singleToolCommand());

        assertThat(result1.toolCalls()).hasSize(1);
        ModelToolCall call1 = result1.toolCalls().getFirst();
        // 第一轮可见历史为空，适配器阶段的临时 ID 为 gemini-call-0
        assertThat(call1.id()).isEqualTo("gemini-call-0");

        // 第二轮：带着第一轮的 call1，模型直接返回 1 个 part（直接是 functionCall）
        ObjectNode turn2Response = mapper.createObjectNode();
        turn2Response.put("modelVersion", "gemini-3.1-flash-lite");
        ObjectNode candidate2 = turn2Response.putArray("candidates").addObject();
        candidate2.put("finishReason", "STOP");
        ObjectNode content2 = candidate2.putObject("content").put("role", "model");
        ArrayNode parts2 = content2.putArray("parts");
        ObjectNode fc2 = parts2.addObject().putObject("functionCall");
        fc2.put("name", "read_file").set("args", mapper.createObjectNode().put("path", "pom.xml"));

        when(http.post(anyString(), anyMap(), any())).thenReturn(turn2Response);

        ModelTurnCommand secondTurnCmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("查任务"),
                        new ModelMessage.Assistant("", List.of(call1)),
                        new ModelMessage.ToolResult(call1.id(), "search_repo", mapper.createObjectNode().put("found", true), false)),
                List.of(new ModelToolDefinition("search_repo", "检索", schema),
                        new ModelToolDefinition("read_file", "读取", schema)),
                false);

        ModelTurnResult result2 = new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", secondTurnCmd);

        assertThat(result2.toolCalls()).hasSize(1);
        ModelToolCall call2 = result2.toolCalls().getFirst();
        // 旧调用仍在本次请求中可见，因此适配器阶段的临时 ID 顺延为 gemini-call-1
        assertThat(call2.id()).isEqualTo("gemini-call-1");
        assertThat(call2.id()).isNotEqualTo(call1.id());
    }

    @Test
    void adapterVisibleIdCounterRestartsWhenRunContextHidesPriorCall() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createFunctionCallResponse(
                "STOP", "list_tasks", mapper.createObjectNode().put("status", "OPEN")));
        ModelTurnCommand command = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.System("sys"),
                        new ModelMessage.User("<RUN_CONTEXT_SUMMARY>已压缩早期调用</RUN_CONTEXT_SUMMARY>"),
                        new ModelMessage.User("继续核对任务")),
                List.of(new ModelToolDefinition("list_tasks", "列出任务", schema)), false);

        ModelTurnResult result = new GeminiModelAdapter(mapper, http).turn(config(), "key", command);

        // 原调用已被压缩，不在本次消息列表中；适配器重新从可见序号 0 起步。
        // AgentRuntimeCoordinator 会在持久化前用 Run 的 tool_calls_used 重定界此临时 ID。
        assertThat(result.toolCalls()).extracting(ModelToolCall::id).containsExactly("gemini-call-0");
    }

    @Test
    void turnRequestMergesConsecutiveSameRoleMessagesAndParallelToolResponses() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(createTextResponse("STOP", "ok"));

        ModelToolCall call1 = new ModelToolCall("gemini-call-0", "list_tasks",
                mapper.createObjectNode().put("status", "OPEN"), "sig-parallel-1");
        ModelToolCall call2 = new ModelToolCall("gemini-call-1", "list_milestones",
                mapper.createObjectNode(), "sig-parallel-2");

        // 连续 2 个 User 消息，接着 1 个 Assistant（含并行 2 个工具调用），再接连续 2 个 ToolResult
        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(
                        new ModelMessage.System("sys"),
                        new ModelMessage.User("用户目标"),
                        new ModelMessage.User("页面附加上下文"),
                        new ModelMessage.Assistant("", List.of(call1, call2)),
                        new ModelMessage.ToolResult("gemini-call-0", "list_tasks",
                                mapper.createObjectNode().put("tasks", 2), false),
                        new ModelMessage.ToolResult("gemini-call-1", "list_milestones",
                                mapper.createObjectNode().put("milestones", 1), false)),
                List.of(),
                false);

        new GeminiModelAdapter(mapper, http).turn(config(), "key", cmd);

        JsonNode body = capturedBody(http);
        JsonNode contents = body.path("contents");

        // 必须严格交替为 user -> model -> user，总共 3 个 content
        assertThat(contents).hasSize(3);

        // Content 0: role "user"，合并了 2 个 user 消息的 text parts
        JsonNode c0 = contents.path(0);
        assertThat(c0.path("role").asText()).isEqualTo("user");
        assertThat(c0.path("parts")).hasSize(2);
        assertThat(c0.path("parts").path(0).path("text").asText()).isEqualTo("用户目标");
        assertThat(c0.path("parts").path(1).path("text").asText()).isEqualTo("页面附加上下文");

        // Content 1: role "model"，包含 2 个 functionCall parts
        JsonNode c1 = contents.path(1);
        assertThat(c1.path("role").asText()).isEqualTo("model");
        assertThat(c1.path("parts")).hasSize(2);
        assertThat(c1.path("parts").path(0).path("functionCall").path("name").asText()).isEqualTo("list_tasks");
        assertThat(c1.path("parts").path(0).path("thoughtSignature").asText()).isEqualTo("sig-parallel-1");
        assertThat(c1.path("parts").path(1).path("functionCall").path("name").asText()).isEqualTo("list_milestones");
        assertThat(c1.path("parts").path(1).path("thoughtSignature").asText()).isEqualTo("sig-parallel-2");

        // Content 2: role "user"，合并了并行执行的 2 个 ToolResult (functionResponse)
        JsonNode c2 = contents.path(2);
        assertThat(c2.path("role").asText()).isEqualTo("user");
        assertThat(c2.path("parts")).hasSize(2);
        assertThat(c2.path("parts").path(0).path("functionResponse").path("name").asText()).isEqualTo("list_tasks");
        assertThat(c2.path("parts").path(1).path("functionResponse").path("name").asText()).isEqualTo("list_milestones");
    }

    @Test
    void turnWithSafetyFinishReasonThrowsRequestRejected() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        ObjectNode response = createTextResponse("SAFETY", "");

        when(http.post(anyString(), anyMap(), any())).thenReturn(response);

        assertThatThrownBy(() -> new GeminiModelAdapter(mapper, http)
                .turn(config(), "key", textOnlyCommand()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_REQUEST_REJECTED));
    }

    private ChatCompletionCommand legacyCommand() {
        return new ChatCompletionCommand(
                null, "system", "user", ChatCompletionCommand.OutputFormat.TEXT,
                ModelPurpose.AGENT, null, List.of(), null);
    }

    private static JsonNode capturedBody(JsonHttpModelClient http) {
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        return body.getValue();
    }
}
