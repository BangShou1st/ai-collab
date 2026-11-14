package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gemini thinking 受控覆盖（默认关闭）：
 * budget 覆盖、level 覆盖、per-model 优先、默认逐字节不变、非法值忽略、覆盖命中不触发 400 重试。
 */
class GeminiThinkingOverrideTest {

    private static final String MODEL = "gemini-3.5-flash-lite";

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonHttpModelClient http;

    @BeforeEach
    void setUp() {
        http = mock(JsonHttpModelClient.class);
        when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenReturn(textResponse());
    }

    @Test
    void budgetOverrideWritesThinkingBudgetEvenWhenThinkingCapabilityDeclared() throws Exception {
        GeminiModelAdapter adapter = adapter(new GeminiThinkingProperties(true, 1, null, Map.of()));

        adapter.complete(config(EnumSet.allOf(ModelCapability.class)), "key", chatCommand());

        JsonNode thinking = capturedBody().path("generationConfig").path("thinkingConfig");
        assertThat(thinking.path("thinkingBudget").asInt(-1)).isEqualTo(1);
        assertThat(thinking.has("thinkingLevel")).isFalse();
    }

    @Test
    void levelOverrideWritesThinkingLevel() throws Exception {
        GeminiModelAdapter adapter = adapter(new GeminiThinkingProperties(true, null, "MINIMAL", Map.of()));

        adapter.complete(config(EnumSet.allOf(ModelCapability.class)), "key", chatCommand());

        JsonNode thinking = capturedBody().path("generationConfig").path("thinkingConfig");
        assertThat(thinking.path("thinkingLevel").asText()).isEqualTo("minimal");
        assertThat(thinking.has("thinkingBudget")).isFalse();
    }

    @Test
    void turnRequestAlsoHonoursOverride() throws Exception {
        GeminiModelAdapter adapter = adapter(new GeminiThinkingProperties(true, 1, null, Map.of()));

        adapter.turn(config(EnumSet.allOf(ModelCapability.class)), "key", turnCommand());

        JsonNode thinking = capturedBody().path("generationConfig").path("thinkingConfig");
        assertThat(thinking.path("thinkingBudget").asInt(-1)).isEqualTo(1);
    }

    @Test
    void perModelOverrideBeatsGlobalDefault() throws Exception {
        GeminiThinkingProperties properties = new GeminiThinkingProperties(true, 5, null,
                Map.of("gemini-2.5-pro", new GeminiThinkingProperties.ModelOverride(null, "minimal")));
        GeminiModelAdapter adapter = adapter(properties);

        adapter.complete(config(EnumSet.allOf(ModelCapability.class), "gemini-2.5-pro"), "key", chatCommand());
        JsonNode perModel = capturedBody().path("generationConfig").path("thinkingConfig");
        assertThat(perModel.path("thinkingLevel").asText()).isEqualTo("minimal");
        assertThat(perModel.has("thinkingBudget")).isFalse();

        adapter.complete(config(EnumSet.allOf(ModelCapability.class), MODEL), "key", chatCommand());
        JsonNode global = capturedBody(2).path("generationConfig").path("thinkingConfig");
        assertThat(global.path("thinkingBudget").asInt(-1)).isEqualTo(5);
    }

    @Test
    void budgetWinsOverLevelInSameScope() throws Exception {
        GeminiModelAdapter adapter = adapter(new GeminiThinkingProperties(true, 1, "high", Map.of()));

        adapter.complete(config(EnumSet.allOf(ModelCapability.class)), "key", chatCommand());

        JsonNode thinking = capturedBody().path("generationConfig").path("thinkingConfig");
        assertThat(thinking.path("thinkingBudget").asInt(-1)).isEqualTo(1);
        assertThat(thinking.has("thinkingLevel")).isFalse();
    }

    @Test
    void disabledSwitchKeepsBodyByteForByteIdentical() throws Exception {
        GeminiThinkingProperties configuredButDisabled = new GeminiThinkingProperties(false, 1, null,
                Map.of(MODEL, new GeminiThinkingProperties.ModelOverride(1, null)));
        GeminiModelAdapter overridden = adapter(configuredButDisabled);
        GeminiModelAdapter legacy = new GeminiModelAdapter(mapper, http);

        overridden.complete(config(EnumSet.allOf(ModelCapability.class)), "key", chatCommand());
        String first = capturedBody().toString();
        legacy.complete(config(EnumSet.allOf(ModelCapability.class)), "key", chatCommand());
        String second = capturedBody(2).toString();

        assertThat(first).isEqualTo(second);
        assertThat(mapper.readTree(first).path("generationConfig").has("thinkingConfig")).isFalse();
    }

    @Test
    void invalidValuesAreIgnoredAndLegacySemanticsRemain() throws Exception {
        // budget 超出 0..32768、level 不在枚举内：都忽略；声明 THINKING → 不发送 thinkingConfig。
        GeminiModelAdapter adapter = adapter(new GeminiThinkingProperties(true, 99999, "extreme", Map.of()));
        adapter.complete(config(EnumSet.allOf(ModelCapability.class)), "key", chatCommand());
        assertThat(capturedBody().path("generationConfig").has("thinkingConfig")).isFalse();

        // 未声明 THINKING → 仍按既有语义发送 thinkingBudget=0。
        adapter.complete(config(EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING)), "key", chatCommand());
        JsonNode thinking = capturedBody(2).path("generationConfig").path("thinkingConfig");
        assertThat(thinking.path("thinkingBudget").asInt(-1)).isZero();
    }

    @Test
    void invalidPerModelOverrideFallsBackToGlobal() throws Exception {
        GeminiThinkingProperties properties = new GeminiThinkingProperties(true, 7, null,
                Map.of(MODEL, new GeminiThinkingProperties.ModelOverride(99999, "extreme")));
        GeminiModelAdapter adapter = adapter(properties);

        adapter.complete(config(EnumSet.allOf(ModelCapability.class)), "key", chatCommand());

        JsonNode thinking = capturedBody().path("generationConfig").path("thinkingConfig");
        assertThat(thinking.path("thinkingBudget").asInt(-1)).isEqualTo(7);
    }

    @Test
    void overrideHitDoesNotRetryOn400() {
        GeminiModelAdapter adapter = adapter(new GeminiThinkingProperties(true, 1, null, Map.of()));
        when(http.post(anyString(), anyMap(), any(JsonNode.class)))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "模型服务返回 HTTP 400"));

        assertThatThrownBy(() -> adapter.complete(
                config(EnumSet.allOf(ModelCapability.class)), "key", chatCommand()))
                .isInstanceOf(BusinessException.class);
        verify(http, times(1)).post(anyString(), anyMap(), any(JsonNode.class));
    }

    @Test
    void openAiCompatibleAdapterIsUnaffectedByGeminiOverride() throws Exception {
        ObjectNode openAiResponse = mapper.createObjectNode();
        ObjectNode choice = openAiResponse.putArray("choices").addObject();
        choice.putObject("message").put("content", "ok");
        choice.put("finish_reason", "stop");
        when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenReturn(openAiResponse);

        OpenAiCompatibleModelAdapter openAi = new OpenAiCompatibleModelAdapter(mapper, http);
        ModelConfiguration config = new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(),
                "test", ModelProviderType.OPENAI_COMPATIBLE, "https://example.test",
                "/v1/chat/completions", null, MODEL, true, 0.2, 6000,
                EnumSet.allOf(ModelCapability.class), OffsetDateTime.now(), OffsetDateTime.now());

        openAi.complete(config, "key", new ChatCompletionCommand("sys", "user"));

        JsonNode body = capturedBody();
        assertThat(body.has("generationConfig")).isFalse();
        assertThat(body.has("thinkingConfig")).isFalse();
    }

    @Test
    void bindsEmptyGlobalValuesAndPerModelKeysWithDots() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfiguration.class)
                .withPropertyValues(
                        "ai.gemini-thinking.enabled=true",
                        // application.yml 的 ${AI_GEMINI_THINKING_BUDGET:} 未设置时绑定为空串，应等价于 null。
                        "ai.gemini-thinking.budget=",
                        "ai.gemini-thinking.level=",
                        "ai.gemini-thinking.models[gemini-3.5-flash-lite].budget=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GeminiThinkingProperties properties = context.getBean(GeminiThinkingProperties.class);
                    assertThat(properties.budget()).isNull();
                    assertThat(properties.level()).isNull();
                    assertThat(properties.resolve("gemini-3.5-flash-lite"))
                            .isEqualTo(new GeminiThinkingProperties.ThinkingSetting(1, null));
                    assertThat(properties.resolve("other-model")).isNull();
                });
    }

    @Test
    void applicationYmlDefaultsBindWithoutEnvironmentOverrides() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(BindingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GeminiThinkingProperties properties = context.getBean(GeminiThinkingProperties.class);
                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.budget()).isNull();
                    assertThat(properties.level()).isNull();
                    assertThat(properties.resolve("gemini-3.5-flash-lite")).isNull();
                });
    }

    @Test
    void applicationYmlBindsEnvStyleNames() {
        Map<String, Object> environment = Map.of(
                // 模拟 staging 的环境变量（Spring 惯用名，与 @ConfigurationProperties 前缀一致）。
                "AI_GEMINI_THINKING_ENABLED", "true",
                "AI_GEMINI_THINKING_BUDGET", "1");
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-env", environment)))
                .withUserConfiguration(BindingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GeminiThinkingProperties properties = context.getBean(GeminiThinkingProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.budget()).isEqualTo(1);
                    assertThat(properties.resolve("gemini-3.5-flash-lite"))
                            .isEqualTo(new GeminiThinkingProperties.ThinkingSetting(1, null));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GeminiThinkingProperties.class)
    static class BindingConfiguration {
    }

    private GeminiModelAdapter adapter(GeminiThinkingProperties properties) {
        return new GeminiModelAdapter(mapper, http, properties);
    }

    private JsonNode capturedBody() {
        return capturedBody(1);
    }

    private JsonNode capturedBody(int expectedCalls) {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(http, times(expectedCalls)).post(anyString(), anyMap(), captor.capture());
        return captor.getAllValues().get(expectedCalls - 1);
    }

    private static ModelConfiguration config(EnumSet<ModelCapability> capabilities) {
        return config(capabilities, MODEL);
    }

    private static ModelConfiguration config(EnumSet<ModelCapability> capabilities, String modelName) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "test",
                ModelProviderType.GEMINI, "https://example.test",
                "/v1beta/models/{model}:generateContent", "encrypted", modelName, true, 0.0, 2048,
                capabilities, now, now);
    }

    private static ChatCompletionCommand chatCommand() {
        return new ChatCompletionCommand("sys", "user");
    }

    private static ModelTurnCommand turnCommand() {
        return new ModelTurnCommand(ModelPurpose.AGENT,
                List.of(new ModelMessage.User("问题")), List.of(), false);
    }

    private static ObjectNode textResponse() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", MODEL);
        ObjectNode candidate = response.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        candidate.putObject("content").put("role", "model").putArray("parts").addObject().put("text", "回答");
        return response;
    }
}
