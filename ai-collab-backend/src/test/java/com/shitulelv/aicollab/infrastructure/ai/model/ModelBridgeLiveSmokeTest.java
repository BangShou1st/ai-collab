package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit, low-volume live smoke for the bridge replacement.
 *
 * <p>Run only with {@code AI_MODEL_BRIDGE_LIVE=true} and explicit provider keys.
 * Keys are read from the environment and never logged or persisted.</p>
 */
@EnabledIfEnvironmentVariable(named = "AI_MODEL_BRIDGE_LIVE", matches = "true")
class ModelBridgeLiveSmokeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void modelScopeOpenAiCompatibleTextStreamAndNativeTools() throws Exception {
        String apiKey = requireEnv("MODELSCOPE_API_KEY");
        String model = env("MODELSCOPE_MODEL", "deepseek-ai/DeepSeek-V4.1-Flash-1017");
        String baseUrl = env("MODELSCOPE_BASE_URL", "https://api-inference.modelscope.cn");
        var config = config(ModelProviderType.OPENAI_COMPATIBLE, baseUrl,
                "/v1/chat/completions", model,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING,
                        ModelCapability.NATIVE_TOOLS));
        var properties = new ProviderExtensionParamsProperties(true,
                List.of(new ProviderExtensionParamsProperties.ModelExtension(
                        "OPENAI_COMPATIBLE", model, false, null)));
        OpenAiCompatibleModelAdapter adapter = new OpenAiCompatibleModelAdapter(
                mapper, bridgeClient(), properties);

        ChatCompletionResult text = adapter.complete(config, apiKey, textCommand());
        assertThat(text.content()).isNotBlank();
        assertThat(text.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(text.model()).isNotBlank();

        AtomicReference<ChatCompletionResult> streamed = new AtomicReference<>();
        AtomicReference<Exception> streamError = new AtomicReference<>();
        CountDownLatch streamDone = new CountDownLatch(1);
        StringBuilder tokens = new StringBuilder();
        adapter.completeStream(config, apiKey, textCommand(), tokens::append,
                result -> {
                    streamed.set(result);
                    streamDone.countDown();
                },
                failure -> {
                    streamError.set(failure);
                    streamDone.countDown();
                });
        assertThat(streamDone.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(streamError.get()).isNull();
        assertThat(tokens).isNotEmpty();
        assertThat(streamed.get().content()).isNotBlank();

        ModelTurnResult tools = adapter.turn(config, apiKey, toolCommand());
        assertThat(tools.toolCalls()).isNotEmpty();
        assertThat(tools.toolCalls().getFirst().name()).isEqualTo("echo");
        assertThat(tools.toolCalls().getFirst().arguments().path("value").asText())
                .isNotBlank();
    }

    @Test
    void geminiTextStreamAndNativeTools() throws Exception {
        String apiKey = requireEnv("GEMINI_API_KEY");
        String model = env("GEMINI_MODEL", "gemini-3.5-flash-lite");
        var config = config(ModelProviderType.GEMINI,
                "https://generativelanguage.googleapis.com",
                "/v1beta/models/{model}:generateContent", model,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING,
                        ModelCapability.NATIVE_TOOLS, ModelCapability.THINKING));
        GeminiModelAdapter adapter = new GeminiModelAdapter(mapper, bridgeClient());

        ChatCompletionResult text = adapter.complete(config, apiKey, textCommand());
        assertThat(text.content()).isNotBlank();
        assertThat(text.provider()).isEqualTo("GEMINI");
        assertThat(text.model()).isNotBlank();

        Thread.sleep(8_000L);
        AtomicReference<ChatCompletionResult> streamed = new AtomicReference<>();
        AtomicReference<Exception> streamError = new AtomicReference<>();
        CountDownLatch streamDone = new CountDownLatch(1);
        StringBuilder tokens = new StringBuilder();
        adapter.completeStream(config, apiKey, textCommand(), tokens::append,
                result -> {
                    streamed.set(result);
                    streamDone.countDown();
                },
                failure -> {
                    streamError.set(failure);
                    streamDone.countDown();
                });
        assertThat(streamDone.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(streamError.get()).isNull();
        assertThat(tokens).isNotEmpty();
        assertThat(streamed.get().content()).isNotBlank();

        Thread.sleep(8_000L);
        ModelTurnResult tools = adapter.turn(config, apiKey, toolCommand());
        assertThat(tools.toolCalls()).isNotEmpty();
        assertThat(tools.toolCalls().getFirst().name()).isEqualTo("echo");
        assertThat(tools.toolCalls().getFirst().id()).startsWith("gemini-call-");
        assertThat(tools.toolCalls().getFirst().arguments().path("value").asText())
                .isNotBlank();
    }

    private ChatCompletionCommand textCommand() {
        return new ChatCompletionCommand(null,
                "You are a concise assistant. Follow the user's requested response format exactly.",
                "只回答 OK，不要输出其他文字。",
                ChatCompletionCommand.OutputFormat.TEXT,
                ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null);
    }

    private ModelTurnCommand toolCommand() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        schema.putArray("required").add("value");
        schema.putObject("properties")
                .putObject("value")
                .put("type", "string")
                .put("description", "the text to echo");
        return new ModelTurnCommand(
                ModelPurpose.AGENT, null, null,
                List.of(new ModelMessage.System(
                                "You must call the echo tool exactly once. Do not answer with text first."),
                        new ModelMessage.User("Call echo with value hello.")),
                List.of(new ModelToolDefinition("echo", "Echo the supplied text", schema)),
                true, UUID.randomUUID());
    }

    private ModelConfiguration config(
            ModelProviderType type,
            String baseUrl,
            String apiPath,
            String model,
            EnumSet<ModelCapability> capabilities) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(),
                type.name() + "-live", type, baseUrl, apiPath, "unused", model,
                true, 0.0, 1024, capabilities, now, now);
    }

    private ModelBridgeClient bridgeClient() {
        HttpTransport transport = new HttpTransport(mapper, new OutboundEndpointPolicy(),
                Duration.ofSeconds(15), Duration.ofMinutes(2),
                com.shitulelv.aicollab.common.net.OutboundHttpClients.builder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .build());
        return new ModelBridgeClient(AdapterRegistry.withBuiltIns(mapper, transport));
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                name + " is required for this explicit live test");
        return value.strip();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
