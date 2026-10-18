package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.KiloGatewayException;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Kilo Gateway Free：预设策略由服务端拥有，推理走共享库客户端，免费模型判定以共享库为准。 */
class KiloGatewayModelTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private UserAiProvider kiloProvider(String model, String baseUrl, String path) {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "Kilo Gateway Free",
                ModelProviderType.OPENAI_COMPATIBLE, baseUrl, path, null, model, true, 0.2, 4000,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE),
                false, OffsetDateTime.now(), OffsetDateTime.now(), "KILO_GATEWAY_FREE");
    }

    private UserAiProvider customProvider() {
        return new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "custom",
                ModelProviderType.OPENAI_COMPATIBLE, "https://api.example.com", "/v1/chat/completions",
                "enc", "gpt-4o-mini", true, 0.2, 1200, EnumSet.of(ModelCapability.CHAT),
                false, OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test void registryKiloPolicyIsServerOwned() {
        var registry = new ProviderPresetRegistry();
        var policy = registry.require(ProviderPresetCode.KILO_GATEWAY_FREE);
        assertThat(policy.protocol()).isEqualTo(ModelProviderType.OPENAI_COMPATIBLE);
        assertThat(registry.completionEndpoint(policy)).isEqualTo("https://ai-gateway.kilo.ai/api/v1/chat/completions");
        assertThat(registry.modelsEndpoint(policy)).isEqualTo("https://ai-gateway.kilo.ai/api/v1/models");
        assertThat(policy.capabilities()).contains(ModelCapability.NATIVE_TOOLS, ModelCapability.STREAMING);
        // 免费模型匿名调用：不由应用侧声明身份头。
        assertThat(policy.userAgent()).isNull();
    }

    @Test void runtimeConfigIgnoresPersistedUrls() {
        var exec = new KiloGatewayModelExecution(new ProviderPresetRegistry(), mapper,
                mock(KiloGatewayTransport.class));
        var config = exec.runtimeConfig(kiloProvider("m:free", "https://evil.example", "/evil"));
        assertThat(config.baseUrl()).isEqualTo("https://ai-gateway.kilo.ai/api/v1");
        assertThat(config.apiPath()).isEqualTo("/chat/completions");
        assertThat(config.modelName()).isEqualTo("m:free");
        assertThat(exec.isKilo(kiloProvider("m:free", "x", "y"))).isTrue();
        assertThat(exec.isKilo(customProvider())).isFalse();
    }

    @Test void turnSendsOpenAiCompatibleBodyAndMapsToolCalls() throws Exception {
        var transport = mock(KiloGatewayTransport.class);
        var response = mapper.readTree("""
                {"model":"m:free","choices":[{"message":{"content":"hi","tool_calls":[
                {"id":"c1","function":{"name":"read","arguments":"{\\"page\\":1}"}}]},"finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":3,"completion_tokens":4}}""");
        when(transport.chat(any())).thenReturn(response);
        var exec = new KiloGatewayModelExecution(new ProviderPresetRegistry(), mapper, transport);

        var command = new ModelTurnCommand(ModelPurpose.AGENT, null, null,
                List.of(new ModelMessage.User("hi")), List.of(), false, UUID.randomUUID());
        ModelTurnResult result = exec.turn(kiloProvider("m:free", "x", "y"), command);

        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("read");
        assertThat(result.usage().inputTokens()).isEqualTo(3);
        assertThat(result.model()).isEqualTo("m:free");

        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(transport).chat(body.capture());
        assertThat(body.getValue().path("model").asText()).isEqualTo("m:free");
        assertThat(body.getValue().path("stream").asBoolean()).isFalse();
        assertThat(body.getValue().path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test void completeForcesJsonByPromptWithoutResponseFormat() throws Exception {
        var transport = mock(KiloGatewayTransport.class);
        when(transport.chat(any())).thenReturn(mapper.readTree(
                "{\"model\":\"m:free\",\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}"));
        var exec = new KiloGatewayModelExecution(new ProviderPresetRegistry(), mapper, transport);

        ChatCompletionResult result = exec.complete(kiloProvider("m:free", "x", "y"),
                new ChatCompletionCommand("sys", "return json", ChatCompletionCommand.OutputFormat.JSON_OBJECT));

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(transport).chat(body.capture());
        assertThat(body.getValue().has("response_format")).isFalse();
        assertThat(body.getValue().path("messages").get(1).path("content").asText())
                .contains("只输出合法 JSON 对象");
    }

    @Test void completeStreamAggregatesTokensAndUsage() throws Exception {
        var transport = mock(KiloGatewayTransport.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> onChunk = invocation.getArgument(1);
            onChunk.accept(mapper.readTree("{\"model\":\"m:free\",\"choices\":[{\"delta\":{\"content\":\"he\"}}]}"));
            onChunk.accept(mapper.readTree("{\"model\":\"m:free\",\"choices\":[{\"delta\":{\"content\":\"y\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}"));
            return null;
        }).when(transport).streamChat(any(), any());
        var exec = new KiloGatewayModelExecution(new ProviderPresetRegistry(), mapper, transport);

        var tokens = new StringBuilder();
        ChatCompletionResult[] done = new ChatCompletionResult[1];
        exec.completeStream(kiloProvider("m:free", "x", "y"), new ChatCompletionCommand("sys", "hi"),
                tokens::append, result -> done[0] = result, error -> {
                    throw new AssertionError(error);
                });

        assertThat(tokens.toString()).isEqualTo("hey");
        assertThat(done[0].content()).isEqualTo("hey");
        assertThat(done[0].completionTokens()).isEqualTo(2);
    }

    // ---- 传输层：共享库错误码归一化 + 免费模型判定 ----

    @Test void admittedModelsKeepsOnlyFreeIdsSorted() throws Exception {
        var client = mock(KiloGatewayClient.class);
        when(client.freeModels()).thenReturn(List.of(
                mapper.readTree("{\"id\":\"poolside/laguna-s-2.1:free\"}"),
                mapper.readTree("{\"id\":\"paid/model\"}"),
                mapper.readTree("{\"id\":\"kilo-auto/free\"}")));
        var transport = new KiloGatewayClientTransport(client);
        assertThat(transport.admittedModels()).containsExactly("kilo-auto/free", "poolside/laguna-s-2.1:free");
    }

    @Test void validateModelRequiresLiveFreeModel() throws Exception {
        var client = mock(KiloGatewayClient.class);
        when(client.freeModels()).thenReturn(List.of(mapper.readTree("{\"id\":\"m:free\"}")));
        var transport = new KiloGatewayClientTransport(client);
        transport.validateModel("m:free");
        assertThatThrownBy(() -> transport.validateModel("paid")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> transport.validateModel("absent:free")).isInstanceOf(BusinessException.class);
    }

    @Test void transportErrorsAreTranslated() {
        var client = mock(KiloGatewayClient.class);
        when(client.chat(any())).thenThrow(new KiloGatewayException(429, "poolside", "quota"));
        var transport = new KiloGatewayClientTransport(client);
        assertThatThrownBy(() -> transport.chat(mapper.createObjectNode()))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
    }

    @Test void onlyRootCauseTimeoutIsReportedAsTimeout() {
        var timedOut = mock(KiloGatewayClient.class);
        when(timedOut.chat(any())).thenThrow(new KiloGatewayException(-1, "",
                "Kilo API connection failed", new java.net.http.HttpTimeoutException("read timeout")));
        assertThatThrownBy(() -> new KiloGatewayClientTransport(timedOut).chat(mapper.createObjectNode()))
                .extracting(failure -> ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.AI_MODEL_TIMEOUT);

        // 连接失败 / SSE 中断 / 解码失败同样报告 -1，不得伪装成超时。
        var interrupted = mock(KiloGatewayClient.class);
        doThrow(new KiloGatewayException(-1, "", "Streaming I/O failure", new java.io.IOException("broken pipe")))
                .when(interrupted).streamChat(any(), any());
        assertThatThrownBy(() -> new KiloGatewayClientTransport(interrupted)
                .streamChat(mapper.createObjectNode(), chunk -> { }))
                .extracting(failure -> ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.AI_PROVIDER_ERROR);
    }

    @Test void libraryFreeOnlyRejectionBecomesValidationError() {
        var client = mock(KiloGatewayClient.class);
        when(client.chat(any())).thenThrow(
                new IllegalArgumentException("Free-only mode rejected non-free model: paid/model"));
        assertThatThrownBy(() -> new KiloGatewayClientTransport(client).chat(mapper.createObjectNode()))
                .extracting(failure -> ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ---- 路由：Kilo 预设行必须分派到 Kilo 执行层 ----

    @Test void turnRoutingDispatchesKiloPresetToKiloExecution() {
        var provider = kiloProvider("m:free", "x", "y");
        var service = mock(UserAiProviderService.class);
        when(service.resolve(any(), any())).thenReturn(provider);
        var zen = mock(ZenModelExecution.class);
        when(zen.isZen(any())).thenReturn(false);
        var kilo = mock(KiloGatewayModelExecution.class);
        when(kilo.isKilo(any())).thenReturn(true);
        var expected = org.mockito.Mockito.mock(ModelTurnResult.class);
        when(kilo.turn(any(), any())).thenReturn(expected);
        var gateway = new RoutingModelTurnGateway(mock(ModelConfigurationRepository.class), service,
                mock(ModelSecretCipher.class), List.of(), zen, kilo);
        var command = new ModelTurnCommand(ModelPurpose.AGENT, null, null,
                List.of(new ModelMessage.User("hi")), List.of(), false, provider.userId());

        assertThat(gateway.turn(command, AiRequestMetadata.fresh())).isEqualTo(expected);
        verify(kilo).turn(provider, command);
    }
}
