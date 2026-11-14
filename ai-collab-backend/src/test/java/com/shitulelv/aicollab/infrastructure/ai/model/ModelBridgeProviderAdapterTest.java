package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.model.ToolCall;
import io.github.bangshou1st.modelbridge.core.protocol.ChatProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelBridgeProviderAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode schema = mapper.createObjectNode()
            .put("type", "object")
            .set("properties", mapper.createObjectNode()
                    .set("title", mapper.createObjectNode().put("type", "string")));

    @Test
    void mapsProviderEndpointIdentityAndTimeoutForAllProtocols() {
        for (ModelProviderType type : List.of(
                ModelProviderType.OPENAI_COMPATIBLE,
                ModelProviderType.ANTHROPIC,
                ModelProviderType.GEMINI)) {
            ModelBridgeTestSupport.FakeChatAdapter fake =
                    fake(protocol(type), ModelBridgeTestSupport.textResponse(
                            "ok", io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP,
                            "mapped-model"));
            ModelConfiguration config = ModelBridgeTestSupport.config(
                    type, "https://example.test", path(type), "mapped-model");

            ModelTurnResult result = adapter(type, fake).turn(
                    config, "secret-key", textCommand());

            assertThat(result.provider()).isEqualTo(type.name());
            assertThat(result.model()).isEqualTo("mapped-model");
            assertThat(fake.lastProvider.protocol()).isEqualTo(protocol(type));
            assertThat(fake.lastProvider.baseUrl()).isEqualTo("https://example.test");
            assertThat(fake.lastProvider.apiKey()).isEqualTo("secret-key");
            assertThat(fake.lastProvider.endpointConfig().chatPath()).isEqualTo(path(type));
            assertThat(fake.lastProvider.requestTimeout()).isBetween(
                    Duration.ofSeconds(1), ModelBridgeProviderAdapter.REQUEST_TIMEOUT);
            if (type == ModelProviderType.ANTHROPIC) {
                assertThat(fake.lastProvider.endpointConfig().anthropicVersion())
                        .isEqualTo("2023-06-01");
            }
        }
    }

    @Test
    void mapsMultiTurnMessagesToolsAndRequiredChoice() {
        var args = mapper.createObjectNode().put("status", "OPEN");
        var call = new ModelToolCall("call_1", "list_tasks", args);
        ModelTurnCommand command = ModelBridgeTestSupport.turnCommand(
                ModelBridgeTestSupport.messages(
                        new ModelMessage.User("先查任务"),
                        new ModelMessage.System("system prompt"),
                        new ModelMessage.Assistant("稍等", List.of(call)),
                        new ModelMessage.ToolResult("call_1", "list_tasks", args, false)),
                List.of(ModelBridgeTestSupport.tool("list_tasks")),
                true);
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(
                ProtocolId.OPENAI_COMPATIBLE, ModelBridgeTestSupport.textResponse(
                        "完成", io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP,
                        "model"));

        adapter(ModelProviderType.OPENAI_COMPATIBLE, fake).turn(
                ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                        "https://example.test", "/v1/chat/completions", "model"),
                "key", command);

        var request = fake.lastRequest;
        assertThat(request.messages()).hasSize(4);
        assertThat(request.messages().getFirst().role())
                .isEqualTo(io.github.bangshou1st.modelbridge.core.model.Role.SYSTEM);
        assertThat(request.messages().get(1).content()).isEqualTo("先查任务");
        assertThat(request.messages().get(2).toolCalls()).hasSize(1);
        assertThat(request.messages().get(2).toolCalls().getFirst().id()).isEqualTo("call_1");
        assertThat(request.messages().get(3).toolCallId()).isEqualTo("call_1");
        assertThat(request.messages().get(3).toolResult()).isEqualTo(args);
        assertThat(request.tools()).hasSize(1);
        assertThat(request.tools().getFirst().name()).isEqualTo("list_tasks");
        assertThat(request.toolChoice())
                .isEqualTo(io.github.bangshou1st.modelbridge.core.model.ToolChoice.REQUIRED);
        assertThat(request.options().temperature()).isEqualTo(0.2);
        assertThat(request.options().maxOutputTokens()).isEqualTo(512);
    }

    @Test
    void anthropicMakesToolErrorsExplicitInToolContent() {
        var args = mapper.createObjectNode().put("status", "OPEN");
        var call = new ModelToolCall("toolu_1", "list_tasks", args);
        ModelTurnCommand command = ModelBridgeTestSupport.turnCommand(
                ModelBridgeTestSupport.messages(
                        new ModelMessage.System("s"),
                        new ModelMessage.User("u"),
                        new ModelMessage.Assistant("", List.of(call)),
                        new ModelMessage.ToolResult("toolu_1", "list_tasks",
                                mapper.createObjectNode().put("message", "boom"), true)),
                List.of(ModelBridgeTestSupport.tool("list_tasks")), false);
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(
                ProtocolId.ANTHROPIC, ModelBridgeTestSupport.textResponse(
                        "done", io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP,
                        "claude"));
        new AnthropicModelAdapter(mapper, ModelBridgeTestSupport.client(fake)).turn(
                ModelBridgeTestSupport.config(ModelProviderType.ANTHROPIC,
                        "https://api.anthropic.com", "/v1/messages", "claude"),
                "key", command);

        var toolMessage = fake.lastRequest.messages().getLast();
        assertThat(toolMessage.role())
                .isEqualTo(io.github.bangshou1st.modelbridge.core.model.Role.TOOL);
        assertThat(toolMessage.content()).startsWith("TOOL_ERROR: ");
        assertThat(toolMessage.toolResult()).isNull();
    }

    @Test
    void mapsToolCallsTextFinishAndUsage() {
        var call = new ToolCall("call_9", "list_tasks",
                mapper.createObjectNode().put("status", "OPEN"), "sig-1");
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(
                ProtocolId.OPENAI_COMPATIBLE, ModelBridgeTestSupport.toolResponse(
                        "先查任务", List.of(call),
                        io.github.bangshou1st.modelbridge.core.model.FinishReason.TOOL_CALLS,
                        "model-x"));

        ModelTurnResult result = new OpenAiCompatibleModelAdapter(
                mapper, ModelBridgeTestSupport.client(fake)).turn(
                ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                        "https://example.test", "/v1/chat/completions", "model-x"),
                "key", toolCommand());

        assertThat(result.content()).isEqualTo("先查任务");
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(result.toolCalls()).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo("call_9");
            assertThat(value.name()).isEqualTo("list_tasks");
            assertThat(value.arguments().path("status").asText()).isEqualTo("OPEN");
            assertThat(value.thoughtSignature()).isEqualTo("sig-1");
        });
        assertThat(result.usage().inputTokens()).isEqualTo(20);
        assertThat(result.usage().outputTokens()).isEqualTo(8);
    }

    @Test
    void rewritesGeminiToolIdsAgainstVisibleHistoryAndKeepsSignature() {
        var prior = new ModelToolCall("gemini-call-0", "list_tasks",
                mapper.createObjectNode());
        var returned = new ToolCall("gemini:scope:0", "list_tasks",
                mapper.createObjectNode().put("status", "OPEN"), "thought-sig");
        ModelTurnCommand command = ModelBridgeTestSupport.turnCommand(
                ModelBridgeTestSupport.messages(
                        new ModelMessage.System("s"),
                        new ModelMessage.User("u"),
                        new ModelMessage.Assistant("", List.of(prior)),
                        new ModelMessage.ToolResult("gemini-call-0", "list_tasks",
                                mapper.createObjectNode(), false)),
                List.of(ModelBridgeTestSupport.tool("list_tasks")), true);
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(
                ProtocolId.GEMINI, ModelBridgeTestSupport.toolResponse(
                        "", List.of(returned),
                        io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP, "gemini-x"));

        ModelTurnResult result = new GeminiModelAdapter(
                mapper, ModelBridgeTestSupport.client(fake)).turn(
                ModelBridgeTestSupport.config(ModelProviderType.GEMINI,
                        "https://generativelanguage.googleapis.com",
                        "/v1beta/models/{model}:generateContent", "gemini-x"),
                "key", command);

        assertThat(result.toolCalls().getFirst().id()).isEqualTo("gemini-call-1");
        assertThat(result.toolCalls().getFirst().thoughtSignature()).isEqualTo("thought-sig");
    }

    @Test
    void geminiThinkingMapsCapabilityAndOverrides() {
        assertGeminiReasoning(EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS),
                GeminiThinkingProperties.disabled(),
                reasoning -> {
                    assertThat(reasoning.enabled()).isFalse();
                    assertThat(reasoning.budgetTokens()).isNull();
                });
        assertGeminiReasoning(EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS,
                        ModelCapability.THINKING),
                GeminiThinkingProperties.disabled(),
                reasoning -> {
                    assertThat(reasoning.enabled()).isNull();
                    assertThat(reasoning.budgetTokens()).isNull();
                });
        assertGeminiReasoning(EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS),
                new GeminiThinkingProperties(true, 7, null, Map.of()),
                reasoning -> assertThat(reasoning.budgetTokens()).isEqualTo(7));
        assertGeminiReasoning(EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS),
                new GeminiThinkingProperties(true, null, "minimal", Map.of()),
                reasoning -> assertThat(reasoning.budgetTokens()).isEqualTo(1));
        assertGeminiReasoning(EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS),
                new GeminiThinkingProperties(true, null, "low", Map.of()),
                reasoning -> assertThat(reasoning.effort()).isEqualTo("low"));
    }

    @Test
    void openAiExtensionParametersArePassedAsProviderOptions() {
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(
                ProtocolId.OPENAI_COMPATIBLE, ModelBridgeTestSupport.textResponse(
                        "ok", io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP,
                        "qwen"));
        var properties = new ProviderExtensionParamsProperties(true,
                List.of(new ProviderExtensionParamsProperties.ModelExtension(
                        "OPENAI_COMPATIBLE", "qwen", false, "low")));

        new OpenAiCompatibleModelAdapter(
                mapper, ModelBridgeTestSupport.client(fake), properties).turn(
                ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                        "https://example.test", "/v1/chat/completions", "qwen"),
                "key", textCommand());

        assertThat(fake.lastRequest.providerOptions())
                .containsEntry("enable_thinking", false)
                .containsEntry("reasoning_effort", "low");
    }

    @Test
    void legacyCompleteMapsPromptToolsAndStructuredOutput() {
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(
                ProtocolId.OPENAI_COMPATIBLE, ModelBridgeTestSupport.textResponse(
                        "{\"title\":\"ok\"}",
                        io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP,
                        "model"));
        var command = new ChatCompletionCommand(
                UUID.randomUUID(), "system", "user",
                ChatCompletionCommand.OutputFormat.JSON_OBJECT, ModelPurpose.PLANNING,
                schema, List.of(ModelBridgeTestSupport.tool("list_tasks")), UUID.randomUUID());

        ChatCompletionResult result = new OpenAiCompatibleModelAdapter(
                mapper, ModelBridgeTestSupport.client(fake)).complete(
                ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                        "https://example.test", "/v1/chat/completions", "model"),
                "key", command);

        assertThat(result.content()).isEqualTo("{\"title\":\"ok\"}");
        assertThat(result.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(fake.lastRequest.messages()).extracting(
                        io.github.bangshou1st.modelbridge.core.model.Message::role)
                .containsExactly(
                        io.github.bangshou1st.modelbridge.core.model.Role.SYSTEM,
                        io.github.bangshou1st.modelbridge.core.model.Role.USER);
        assertThat(fake.lastRequest.structuredOutput().mode())
                .isEqualTo(io.github.bangshou1st.modelbridge.core.model.StructuredOutput.Mode.JSON_SCHEMA);
        assertThat(fake.lastRequest.structuredOutput().schema()).isEqualTo(schema);
        assertThat(fake.lastRequest.tools()).hasSize(1);
        assertThat(fake.lastRequest.toolChoice())
                .isEqualTo(io.github.bangshou1st.modelbridge.core.model.ToolChoice.AUTO);
    }

    @Test
    void promptJsonStaysApplicationValidated() {
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(
                ProtocolId.OPENAI_COMPATIBLE, ModelBridgeTestSupport.textResponse(
                        "{}", io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP,
                        "model"));
        var command = new ChatCompletionCommand("system", "user",
                ChatCompletionCommand.OutputFormat.PROMPT_JSON);

        new OpenAiCompatibleModelAdapter(mapper, ModelBridgeTestSupport.client(fake)).complete(
                ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                        "https://example.test", "/v1/chat/completions", "model"),
                "key", command);

        assertThat(fake.lastRequest.structuredOutput().mode())
                .isEqualTo(io.github.bangshou1st.modelbridge.core.model.StructuredOutput.Mode.NONE);
    }

    @Test
    void streamsContentAndUsageThroughLegacyContract() throws Exception {
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(ProtocolId.OPENAI_COMPATIBLE, null);
        fake.chunks = List.of(
                ModelBridgeTestSupport.content("你", "model-stream"),
                ModelBridgeTestSupport.content("好", "model-stream"),
                ModelBridgeTestSupport.done(
                        io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP,
                        11, 3, "model-stream"));
        StringBuilder tokens = new StringBuilder();
        AtomicReference<ChatCompletionResult> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        new OpenAiCompatibleModelAdapter(mapper, ModelBridgeTestSupport.client(fake))
                .completeStream(
                        ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                                "https://example.test", "/v1/chat/completions", "model-stream"),
                        "key",
                        new ChatCompletionCommand("system", "user"),
                        tokens::append,
                        value -> {
                            result.set(value);
                            finished.countDown();
                        },
                        failure -> {
                            error.set(failure);
                            finished.countDown();
                        });

        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        assertThat(tokens.toString()).isEqualTo("你好");
        assertThat(result.get().content()).isEqualTo("你好");
        assertThat(result.get().promptTokens()).isEqualTo(11);
        assertThat(result.get().completionTokens()).isEqualTo(3);
        assertThat(result.get().model()).isEqualTo("model-stream");
    }

    @Test
    void streamFailureCarriesUsageAndStopsBeforeDone() throws Exception {
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(ProtocolId.OPENAI_COMPATIBLE, null);
        fake.streamFailure = ModelBridgeTestSupport.bridgeFailure(
                ErrorCategory.LENGTH, 0, new TokenUsage(9, 6));
        AtomicReference<ChatCompletionResult> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        new OpenAiCompatibleModelAdapter(mapper, ModelBridgeTestSupport.client(fake))
                .completeStream(
                        ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                                "https://example.test", "/v1/chat/completions", "model"),
                        "key",
                        new ChatCompletionCommand("system", "user"),
                        token -> {
                        },
                        value -> {
                            result.set(value);
                            finished.countDown();
                        },
                        failure -> {
                            error.set(failure);
                            finished.countDown();
                        });

        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isNull();
        assertThat(error.get()).isInstanceOfSatisfying(
                ProviderResponseFailure.class, failure -> {
                    assertThat(failure.getErrorCode())
                            .isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
                    assertThat(failure.promptTokens()).isEqualTo(9);
                    assertThat(failure.completionTokens()).isEqualTo(6);
                });
    }

    @Test
    void rejectsTruncatedTurnsAndEmptyResponses() {
        ModelBridgeTestSupport.FakeChatAdapter truncated = fake(
                ProtocolId.OPENAI_COMPATIBLE, new io.github.bangshou1st.modelbridge.core.model.ChatResponse(
                        "partial", null, List.of(),
                        io.github.bangshou1st.modelbridge.core.model.FinishReason.LENGTH,
                        new TokenUsage(3, 4), "test", "m", 1, Map.of()));
        assertThatThrownBy(() -> new OpenAiCompatibleModelAdapter(
                mapper, ModelBridgeTestSupport.client(truncated)).turn(
                ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                        "https://example.test", "/v1/chat/completions", "m"),
                "key", textCommand()))
                .isInstanceOfSatisfying(ProviderResponseFailure.class, failure -> {
                    assertThat(failure.getErrorCode())
                            .isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
                    assertThat(failure.promptTokens()).isEqualTo(3);
                });

        ModelBridgeTestSupport.FakeChatAdapter empty = fake(
                ProtocolId.OPENAI_COMPATIBLE, new io.github.bangshou1st.modelbridge.core.model.ChatResponse(
                        "", null, List.of(),
                        io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP,
                        TokenUsage.empty(), "test", "m", 1, Map.of()));
        assertThatThrownBy(() -> new OpenAiCompatibleModelAdapter(
                mapper, ModelBridgeTestSupport.client(empty)).turn(
                ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                        "https://example.test", "/v1/chat/completions", "m"),
                "key", textCommand()))
                .isInstanceOfSatisfying(ProviderResponseFailure.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE));
    }

    @Test
    void translatesBridgeErrorsWithoutLeakingBodies() {
        assertMappedError(ErrorCategory.AUTHENTICATION, 401,
                ErrorCode.AI_MODEL_CREDENTIAL_INVALID);
        assertMappedError(ErrorCategory.AUTHORIZATION, 403,
                ErrorCode.AI_PROVIDER_REQUEST_REJECTED);
        assertMappedError(ErrorCategory.RATE_LIMIT, 429,
                ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
        assertMappedError(ErrorCategory.TIMEOUT, 504, ErrorCode.AI_MODEL_TIMEOUT);
        assertMappedError(ErrorCategory.BAD_REQUEST, 404, ErrorCode.AI_PROVIDER_NOT_FOUND);
        assertMappedError(ErrorCategory.NETWORK_POLICY, 403,
                ErrorCode.AI_PROVIDER_REQUEST_REJECTED);
        assertMappedError(ErrorCategory.SCHEMA, 502, ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        assertMappedError(ErrorCategory.UNAVAILABLE, 503, ErrorCode.AI_PROVIDER_ERROR);
    }

    @Test
    void streamTimesOutWhenBridgeNeverCompletes() throws Exception {
        ModelBridgeTestSupport.FakeChatAdapter stuck = new ModelBridgeTestSupport.FakeChatAdapter(
                ProtocolId.OPENAI_COMPATIBLE) {
            @Override
            public StreamHandle stream(
                    ProviderConfig provider,
                    io.github.bangshou1st.modelbridge.core.model.ChatRequest request,
                    Consumer<StreamChunk> consumer) {
                return new StreamHandle() {
                    private volatile boolean cancelled;

                    @Override
                    public void cancel() {
                        cancelled = true;
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled;
                    }

                    @Override
                    public boolean isDone() {
                        return false;
                    }

                    @Override
                    public void await() {
                    }
                };
            }
        };
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        AiRequestDeadline deadline = new AiRequestDeadline();
        deadline.limit(Duration.ofMillis(80));
        try (deadline) {
            new OpenAiCompatibleModelAdapter(mapper, ModelBridgeTestSupport.client(stuck))
                    .completeStream(
                            ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                                    "https://example.test", "/v1/chat/completions", "m"),
                            "key",
                            new ChatCompletionCommand("system", "user"),
                            token -> {
                            },
                            value -> {
                                finished.countDown();
                            },
                            failure -> {
                                error.set(failure);
                                finished.countDown();
                            });
        }

        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error.get()).getErrorCode())
                .isEqualTo(ErrorCode.AI_MODEL_TIMEOUT);
    }

    private void assertMappedError(
            ErrorCategory category, int status, ErrorCode expected) {
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(ProtocolId.OPENAI_COMPATIBLE, null);
        fake.failure = ModelBridgeTestSupport.bridgeFailure(category, status, TokenUsage.empty());
        assertThatThrownBy(() -> new OpenAiCompatibleModelAdapter(
                mapper, ModelBridgeTestSupport.client(fake)).turn(
                ModelBridgeTestSupport.config(ModelProviderType.OPENAI_COMPATIBLE,
                        "https://example.test", "/v1/chat/completions", "m"),
                "key", textCommand()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }

    private void assertGeminiReasoning(
            EnumSet<ModelCapability> capabilities,
            GeminiThinkingProperties properties,
            Consumer<io.github.bangshou1st.modelbridge.core.model.ReasoningOptions> assertion) {
        ModelBridgeTestSupport.FakeChatAdapter fake = fake(
                ProtocolId.GEMINI, ModelBridgeTestSupport.textResponse(
                        "ok", io.github.bangshou1st.modelbridge.core.model.FinishReason.STOP,
                        "gemini"));
        var config = new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(),
                "gemini", ModelProviderType.GEMINI,
                "https://generativelanguage.googleapis.com",
                "/v1beta/models/{model}:generateContent", "encrypted", "gemini",
                true, 0.2, 512, capabilities, java.time.OffsetDateTime.now(),
                java.time.OffsetDateTime.now());
        new GeminiModelAdapter(mapper, ModelBridgeTestSupport.client(fake), properties)
                .turn(config, "key", textCommand());
        assertion.accept(fake.lastRequest.reasoning());
    }

    private ModelTurnCommand textCommand() {
        return ModelBridgeTestSupport.turnCommand(
                ModelBridgeTestSupport.messages(
                        new ModelMessage.System("system"),
                        new ModelMessage.User("hello")),
                List.of(), false);
    }

    private ModelTurnCommand toolCommand() {
        return ModelBridgeTestSupport.turnCommand(
                ModelBridgeTestSupport.messages(
                        new ModelMessage.System("system"),
                        new ModelMessage.User("hello")),
                List.of(ModelBridgeTestSupport.tool("list_tasks")), true);
    }

    private static ProtocolId protocol(ModelProviderType type) {
        return switch (type) {
            case OPENAI_COMPATIBLE -> ProtocolId.OPENAI_COMPATIBLE;
            case ANTHROPIC -> ProtocolId.ANTHROPIC;
            case GEMINI -> ProtocolId.GEMINI;
            case LOCAL_CUSTOM -> throw new IllegalArgumentException();
        };
    }

    private static String path(ModelProviderType type) {
        return switch (type) {
            case OPENAI_COMPATIBLE -> "/v1/chat/completions";
            case ANTHROPIC -> "/v1/messages";
            case GEMINI -> "/v1beta/models/{model}:generateContent";
            case LOCAL_CUSTOM -> throw new IllegalArgumentException();
        };
    }

    private static ModelBridgeProviderAdapter adapter(
            ModelProviderType type, ModelBridgeTestSupport.FakeChatAdapter fake) {
        return new ModelBridgeProviderAdapter(type, new ObjectMapper(),
                ModelBridgeTestSupport.client(fake),
                GeminiThinkingProperties.disabled(),
                ProviderExtensionParamsProperties.disabled());
    }

    private static ModelBridgeTestSupport.FakeChatAdapter fake(
            ProtocolId protocol,
            io.github.bangshou1st.modelbridge.core.model.ChatResponse response) {
        var fake = new ModelBridgeTestSupport.FakeChatAdapter(protocol);
        fake.response = response;
        return fake;
    }
}
