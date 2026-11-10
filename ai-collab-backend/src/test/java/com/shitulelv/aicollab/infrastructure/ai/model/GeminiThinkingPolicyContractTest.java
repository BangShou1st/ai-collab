package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gemini thinkingConfig 策略契约：
 * <ul>
 *   <li>未声明 {@link ModelCapability#THINKING}：generationConfig 必须显式携带
 *       thinkingConfig.thinkingBudget=0（思考型模型如 gemini-2.5/3.x 默认思考会挤占输出预算并
 *       破坏 temperature=0 确定性契约）。</li>
 *   <li>声明 THINKING：不得发送 thinkingConfig（保持模型默认思考行为）。</li>
 *   <li>旧模型 400 拒绝 thinkingConfig 时：去除该字段后有界重试一次。</li>
 * </ul>
 */
class GeminiThinkingPolicyContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ModelConfiguration config(EnumSet<ModelCapability> capabilities) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                UUID.randomUUID(), UUID.randomUUID(), "test", ModelProviderType.GEMINI,
                "https://example.com", "/v1beta/models/{model}:generateContent",
                "encrypted", "gemini-test", true, 0.0, 2048,
                capabilities, now, now);
    }

    private ChatCompletionCommand chatCommand() {
        return new ChatCompletionCommand(
                null, "sys", "user", ChatCompletionCommand.OutputFormat.TEXT,
                ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null);
    }

    private ModelTurnCommand turnCommand() {
        return new ModelTurnCommand(
                ModelPurpose.AGENT, null, null,
                List.of(new ModelMessage.System("sys"), new ModelMessage.User("问题")),
                List.of(), false, null);
    }

    private ObjectNode textResponse() {
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", "gemini-test");
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        candidate.putObject("content").put("role", "model").putArray("parts").addObject().put("text", "回答");
        return response;
    }

    @Test
    void chatWithoutThinkingCapability_sendsThinkingBudgetZero() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(textResponse());

        new GeminiModelAdapter(mapper, http).complete(
                config(EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.USAGE)),
                "key", chatCommand());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        JsonNode generation = body.getValue().path("generationConfig");
        assertThat(generation.path("thinkingConfig").path("thinkingBudget").asInt(-1)).isZero();
    }

    @Test
    void chatWithThinkingCapability_omitsThinkingConfig() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(textResponse());

        new GeminiModelAdapter(mapper, http).complete(config(EnumSet.allOf(ModelCapability.class)), "key", chatCommand());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        assertThat(body.getValue().path("generationConfig").has("thinkingConfig")).isFalse();
    }

    @Test
    void turnWithoutThinkingCapability_sendsThinkingBudgetZero() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any())).thenReturn(textResponse());

        new GeminiModelAdapter(mapper, http).turn(
                config(EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS)),
                "key", turnCommand());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).post(anyString(), anyMap(), body.capture());
        JsonNode generation = body.getValue().path("generationConfig");
        assertThat(generation.path("thinkingConfig").path("thinkingBudget").asInt(-1)).isZero();
    }

    @Test
    void legacyModelRejectingThinkingConfig_retriesOnceWithoutIt() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        // postWithThinkingFallback 会原位修改请求体（移除 thinkingConfig）后重试，
        // 因此必须在调用时深拷贝快照，不能依赖事后 ArgumentCaptor（同一引用）。
        List<JsonNode> sent = new ArrayList<>();
        when(http.post(anyString(), anyMap(), any())).thenAnswer(invocation -> {
            sent.add(((ObjectNode) invocation.getArgument(2)).deepCopy());
            if (sent.size() == 1) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "模型服务返回 HTTP 400");
            }
            return textResponse();
        });

        ChatCompletionResult result = new GeminiModelAdapter(mapper, http).complete(
                config(EnumSet.of(ModelCapability.CHAT, ModelCapability.USAGE)), "key", chatCommand());

        assertThat(result.content()).isEqualTo("回答");
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).path("generationConfig").has("thinkingConfig")).isTrue();
        assertThat(sent.get(1).path("generationConfig").has("thinkingConfig")).isFalse();
    }

    @Test
    void nonBadRequest_doesNotRetry() {
        JsonHttpModelClient http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID, "密钥无效"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GeminiModelAdapter(mapper, http)
                .complete(config(EnumSet.of(ModelCapability.CHAT, ModelCapability.USAGE)), "key", chatCommand()))
                .isInstanceOf(BusinessException.class);
        verify(http, times(1)).post(anyString(), anyMap(), any());
    }
}
