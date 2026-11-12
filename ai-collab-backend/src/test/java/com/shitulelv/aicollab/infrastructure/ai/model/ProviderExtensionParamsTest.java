package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
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

import java.time.OffsetDateTime;
import java.util.EnumSet;
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
 * D1：OpenAI 兼容适配器的 provider 扩展参数白名单透传。
 *
 * <p>覆盖：白名单命中、非法取值忽略、开关关闭保持旧请求体、原生 Tool Calling
 * （{@code turnRequest}）同样生效、非 OPENAI_COMPATIBLE 不受影响、列表配置绑定。</p>
 */
class ProviderExtensionParamsTest {

    private static final String MODEL_NAME = "deepseek-ai/DeepSeek-V4.1-Flash-1017";

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonHttpModelClient http;
    private JsonNode response;

    @BeforeEach
    void setUp() {
        http = mock(JsonHttpModelClient.class);
        response = response();
    }

    @Test
    void whitelistedParamsAreInjectedIntoCompleteBody() throws Exception {
        when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenReturn(response);
        OpenAiCompatibleModelAdapter adapter = adapter(new ProviderExtensionParamsProperties(true,
                List.of(new ProviderExtensionParamsProperties.ModelExtension(
                        "OPENAI_COMPATIBLE", MODEL_NAME, false, "none"))));

        adapter.complete(config(ModelProviderType.OPENAI_COMPATIBLE), "test-key",
                new ChatCompletionCommand("system", "user"));

        JsonNode body = capturedBody();
        assertThat(body.path("enable_thinking").isBoolean()).isTrue();
        assertThat(body.path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("none");
        // 既有字段语义不变
        assertThat(body.path("model").asText()).isEqualTo(MODEL_NAME);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(6000);
        assertThat(body.path("messages")).hasSize(2);
    }

    @Test
    void invalidWhitelistValuesAreIgnored() throws Exception {
        when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenReturn(response);
        OpenAiCompatibleModelAdapter adapter = adapter(new ProviderExtensionParamsProperties(true,
                List.of(new ProviderExtensionParamsProperties.ModelExtension(
                        "OPENAI_COMPATIBLE", MODEL_NAME, false, "extreme"))));

        adapter.complete(config(ModelProviderType.OPENAI_COMPATIBLE), "test-key",
                new ChatCompletionCommand("system", "user"));

        JsonNode body = capturedBody();
        assertThat(body.path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @Test
    void disabledSwitchKeepsBodyByteForByteIdentical() throws Exception {
        when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenReturn(response);
        ProviderExtensionParamsProperties configuredButDisabled = new ProviderExtensionParamsProperties(
                false, List.of(new ProviderExtensionParamsProperties.ModelExtension(
                        "OPENAI_COMPATIBLE", MODEL_NAME, false, null)));
        OpenAiCompatibleModelAdapter featureOnButDisabled = adapter(configuredButDisabled);
        OpenAiCompatibleModelAdapter legacy = new OpenAiCompatibleModelAdapter(
                mapper, http, new OpenCodeZenRequestFactory("test-agent"));

        featureOnButDisabled.complete(config(ModelProviderType.OPENAI_COMPATIBLE), "test-key",
                new ChatCompletionCommand("system", "user"));
        String first = capturedBody().toString();
        legacy.complete(config(ModelProviderType.OPENAI_COMPATIBLE), "test-key",
                new ChatCompletionCommand("system", "user"));
        String second = capturedBody(2).toString();

        assertThat(first).isEqualTo(second);
        assertThat(mapper.readTree(first).has("enable_thinking")).isFalse();
        assertThat(mapper.readTree(first).has("reasoning_effort")).isFalse();
    }

    @Test
    void turnRequestAlsoCarriesWhitelistedParams() throws Exception {
        when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenReturn(response);
        OpenAiCompatibleModelAdapter adapter = adapter(new ProviderExtensionParamsProperties(true,
                List.of(new ProviderExtensionParamsProperties.ModelExtension(
                        "openai_compatible", MODEL_NAME, false, "low"))));

        adapter.turn(config(ModelProviderType.OPENAI_COMPATIBLE), "test-key",
                new ModelTurnCommand(ModelPurpose.AGENT,
                        List.of(new ModelMessage.User("query")),
                        List.of(new ModelToolDefinition("get_task", "query",
                                mapper.createObjectNode().put("type", "object"))),
                        false));

        JsonNode body = capturedBody();
        assertThat(body.path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(body.path("tools").findValuesAsText("name")).containsExactly("get_task");
    }

    @Test
    void geminiConfigDoesNotReceiveExtensionParams() throws Exception {
        when(http.post(anyString(), anyMap(), any(JsonNode.class))).thenReturn(response);
        OpenAiCompatibleModelAdapter adapter = adapter(new ProviderExtensionParamsProperties(true,
                List.of(new ProviderExtensionParamsProperties.ModelExtension(
                        "OPENAI_COMPATIBLE", MODEL_NAME, false, null))));

        adapter.complete(config(ModelProviderType.GEMINI), "test-key",
                new ChatCompletionCommand("system", "user"));

        JsonNode body = capturedBody();
        assertThat(body.has("enable_thinking")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @Test
    void listBindingIsStableForModelNamesWithSlashesAndDots() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfiguration.class)
                .withPropertyValues(
                        "ai.provider-extension-params.enabled=true",
                        "ai.provider-extension-params.models[0].providerType=OPENAI_COMPATIBLE",
                        "ai.provider-extension-params.models[0].modelName=" + MODEL_NAME,
                        "ai.provider-extension-params.models[0].enableThinking=false",
                        "ai.provider-extension-params.models[0].reasoningEffort=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProviderExtensionParamsProperties properties =
                            context.getBean(ProviderExtensionParamsProperties.class);
                    assertThat(properties.getModels()).hasSize(1);
                    assertThat(properties.forModel(ModelProviderType.OPENAI_COMPATIBLE, MODEL_NAME))
                            .containsEntry("enable_thinking", false)
                            .containsEntry("reasoning_effort", "none");
                    assertThat(properties.forModel(ModelProviderType.OPENAI_COMPATIBLE, "other-model"))
                            .isEmpty();
                });
    }

    @Test
    void applicationYmlBindsEmptyModelListByDefault() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(BindingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProviderExtensionParamsProperties properties =
                            context.getBean(ProviderExtensionParamsProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getModels()).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProviderExtensionParamsProperties.class)
    static class BindingConfiguration {
    }

    private OpenAiCompatibleModelAdapter adapter(ProviderExtensionParamsProperties properties) {
        return new OpenAiCompatibleModelAdapter(mapper, http,
                new OpenCodeZenRequestFactory("test-agent"), properties);
    }

    private JsonNode capturedBody() {
        return capturedBody(1);
    }

    private JsonNode capturedBody(int expectedCalls) {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(http, times(expectedCalls)).post(anyString(), anyMap(), captor.capture());
        return captor.getAllValues().get(expectedCalls - 1);
    }

    private static ModelConfiguration config(ModelProviderType providerType) {
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "test", providerType,
                "https://example.test", "/v1/chat/completions", null, MODEL_NAME, true, 0.2, 6000,
                EnumSet.allOf(ModelCapability.class), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private JsonNode response() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.putObject("message").put("content", "ok");
        choice.put("finish_reason", "stop");
        root.put("model", MODEL_NAME);
        root.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 1);
        return root;
    }
}
