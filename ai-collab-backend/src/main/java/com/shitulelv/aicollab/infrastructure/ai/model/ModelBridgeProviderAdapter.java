package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelContentPreview;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ChatOptions;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.EndpointConfig;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.Message;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ReasoningOptions;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.StructuredOutput;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.model.ToolCall;
import io.github.bangshou1st.modelbridge.core.model.ToolChoice;
import io.github.bangshou1st.modelbridge.core.model.ToolDefinition;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Provider-neutral adapter backed by AI Model Bridge.
 * Protocol differences stay in the bridge; this class only maps the application's model contracts.
 */
public class ModelBridgeProviderAdapter implements ModelProviderAdapter, ModelTurnProviderAdapter {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final ModelProviderType providerType;
    private final ProtocolId protocol;
    private final ObjectMapper mapper;
    private final ModelBridgeClient client;
    private final GeminiThinkingProperties geminiThinking;
    private final ProviderExtensionParamsProperties extensionParams;

    public ModelBridgeProviderAdapter(
            ModelProviderType providerType,
            ObjectMapper mapper,
            ModelBridgeClient client,
            GeminiThinkingProperties geminiThinking,
            ProviderExtensionParamsProperties extensionParams) {
        this.providerType = providerType;
        this.protocol = switch (providerType) {
            case OPENAI_COMPATIBLE -> ProtocolId.OPENAI_COMPATIBLE;
            case ANTHROPIC -> ProtocolId.ANTHROPIC;
            case GEMINI -> ProtocolId.GEMINI;
            case LOCAL_CUSTOM -> throw new IllegalArgumentException("LOCAL_CUSTOM 不参与 Chat 适配器路由");
        };
        this.mapper = mapper;
        this.client = client;
        this.geminiThinking = geminiThinking == null
                ? GeminiThinkingProperties.disabled() : geminiThinking;
        this.extensionParams = extensionParams == null
                ? ProviderExtensionParamsProperties.disabled() : extensionParams;
    }

    @Override
    public ModelProviderType providerType() {
        return providerType;
    }

    @Override
    public ChatCompletionResult complete(
            ModelConfiguration configuration, String apiKey, ChatCompletionCommand command) {
        long started = System.nanoTime();
        try {
            ChatResponse response = client.complete(provider(configuration, apiKey),
                    chatRequest(configuration, command));
            rejectTerminalFinish(response.finishReason(), response.usage());
            if (response.content().isBlank()) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "MODEL_BRIDGE / EMPTY_CONTENT", usageInput(response.usage()),
                        usageOutput(response.usage()));
            }
            return result(configuration, response.content().strip(), response.model(),
                    usageInput(response.usage()), usageOutput(response.usage()), started);
        } catch (BusinessException exception) {
            throw exception;
        } catch (ModelBridgeException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
    }

    @Override
    public void completeStream(
            ModelConfiguration configuration,
            String apiKey,
            ChatCompletionCommand command,
            Consumer<String> onToken,
            Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError) {
        try {
            long started = System.nanoTime();
            StringBuilder content = new StringBuilder();
            String[] responseModel = {configuration.modelName()};
            FinishReason[] finish = {null};
            TokenUsage[] usage = {TokenUsage.empty()};
            ModelContentPreview.Observer preview = ModelContentPreview.capture();

            ProviderConfig provider = provider(configuration, apiKey);
            ChatRequest request = chatRequest(configuration, command);
            StreamHandle handle = client.openStream(provider, request, chunk -> {
                if (chunk.model() != null && !chunk.model().isBlank()) {
                    responseModel[0] = chunk.model();
                }
                if (chunk.finishReason() != null) {
                    finish[0] = chunk.finishReason();
                }
                if (!chunk.usage().isEmpty()) {
                    usage[0] = chunk.usage();
                }
                if (chunk.type() == StreamChunk.Type.CONTENT && !chunk.delta().isEmpty()) {
                    content.append(chunk.delta());
                    onToken.accept(chunk.delta());
                    ModelContentPreview.push(preview, content.toString());
                }
            });
            awaitBounded(handle);
            rejectTerminalFinish(finish[0], usage[0]);
            if (content.toString().isBlank()) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "MODEL_BRIDGE_STREAM / EMPTY_CONTENT", usageInput(usage[0]),
                        usageOutput(usage[0]));
            }
            ModelContentPreview.finish(preview, content.toString());
            onDone.accept(result(configuration, content.toString().strip(), responseModel[0],
                    usageInput(usage[0]), usageOutput(usage[0]), started));
        } catch (BusinessException exception) {
            onError.accept(exception);
        } catch (ModelBridgeException exception) {
            onError.accept(translate(exception));
        } catch (Exception exception) {
            onError.accept(new BusinessException(ErrorCode.AI_PROVIDER_ERROR));
        }
    }

    @Override
    public ModelTurnResult turn(
            ModelConfiguration configuration, String apiKey, ModelTurnCommand command) {
        long started = System.nanoTime();
        try {
            ChatResponse response = client.complete(provider(configuration, apiKey),
                    turnRequest(configuration, command));
            rejectTerminalFinish(response.finishReason(), response.usage());
            List<ModelToolCall> toolCalls = toolCalls(configuration, command, response.toolCalls());
            if (response.content().isBlank() && toolCalls.isEmpty()) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "MODEL_BRIDGE / NO_TEXT_OR_TOOL_CALLS", usageInput(response.usage()),
                        usageOutput(response.usage()));
            }
            long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            return new ModelTurnResult(
                    response.content(),
                    toolCalls,
                    finishReason(response.finishReason()),
                    usage(response.usage()),
                    providerType.name(),
                    response.model() == null || response.model().isBlank()
                            ? configuration.modelName() : response.model(),
                    latencyMs);
        } catch (BusinessException exception) {
            throw exception;
        } catch (ModelBridgeException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
    }

    private ProviderConfig provider(ModelConfiguration configuration, String apiKey) {
        EndpointConfig.Builder endpoints = EndpointConfig.builder();
        if (configuration.apiPath() != null && !configuration.apiPath().isBlank()) {
            endpoints.chatPath(configuration.apiPath());
        }
        if (providerType == ModelProviderType.ANTHROPIC) {
            endpoints.anthropicVersion("2023-06-01");
        }
        long timeoutMillis = AiRequestDeadline.timeoutMillis(REQUEST_TIMEOUT);
        return ProviderConfig.builder(configuration.id().toString(), protocol)
                .baseUrl(configuration.baseUrl())
                .apiKey(apiKey)
                .connectTimeout(CONNECT_TIMEOUT)
                .requestTimeout(Duration.ofMillis(Math.max(1L, timeoutMillis)))
                .endpointConfig(endpoints.build())
                .build();
    }

    private ChatRequest chatRequest(ModelConfiguration configuration, ChatCompletionCommand command) {
        ChatRequest.Builder request = ChatRequest.builder(configuration.modelName())
                .messages(legacyMessages(command))
                .options(chatOptions(configuration))
                .tools(tools(command.tools()))
                .toolChoice(ToolChoice.AUTO)
                .structuredOutput(structuredOutput(command))
                .reasoning(reasoning(configuration))
                .requestId(UUID.randomUUID().toString());
        providerOptions(configuration).forEach(request::providerOption);
        return request.build();
    }

    private ChatRequest turnRequest(ModelConfiguration configuration, ModelTurnCommand command) {
        ChatRequest.Builder request = ChatRequest.builder(configuration.modelName())
                .messages(turnMessages(command.messages()))
                .options(chatOptions(configuration))
                .tools(tools(command.tools()))
                .toolChoice(command.toolsRequired() ? ToolChoice.REQUIRED : ToolChoice.AUTO)
                .structuredOutput(StructuredOutput.none())
                .reasoning(reasoning(configuration))
                .requestId(UUID.randomUUID().toString());
        providerOptions(configuration).forEach(request::providerOption);
        return request.build();
    }

    private ChatOptions chatOptions(ModelConfiguration configuration) {
        return ChatOptions.builder()
                .temperature(configuration.temperature())
                .maxOutputTokens(AiRequestOutputCap.effective(configuration.maxOutputTokens()))
                .build();
    }

    private List<Message> legacyMessages(ChatCompletionCommand command) {
        List<Message> messages = new ArrayList<>(2);
        if (command.systemPrompt() != null && !command.systemPrompt().isBlank()) {
            messages.add(Message.system(command.systemPrompt()));
        }
        if (command.userPrompt() != null && !command.userPrompt().isBlank()) {
            messages.add(Message.user(command.userPrompt()));
        }
        return messages;
    }

    private List<Message> turnMessages(List<ModelMessage> source) {
        List<ModelMessage> ordered = new ArrayList<>(source.size());
        for (ModelMessage message : source) {
            if (message instanceof ModelMessage.System) {
                ordered.add(message);
            }
        }
        for (ModelMessage message : source) {
            if (!(message instanceof ModelMessage.System)) {
                ordered.add(message);
            }
        }

        List<Message> messages = new ArrayList<>(ordered.size());
        for (ModelMessage message : ordered) {
            switch (message) {
                case ModelMessage.System value -> messages.add(Message.system(value.content()));
                case ModelMessage.User value -> messages.add(Message.user(value.content()));
                case ModelMessage.Assistant value -> messages.add(Message.assistant(
                        value.content(),
                        value.toolCalls().stream()
                                .map(call -> new ToolCall(call.id(), call.name(),
                                        call.arguments(), call.thoughtSignature()))
                                .toList()));
                case ModelMessage.ToolResult value -> messages.add(toolResultMessage(value));
            }
        }
        return messages;
    }

    private Message toolResultMessage(ModelMessage.ToolResult value) {
        if (providerType != ModelProviderType.ANTHROPIC) {
            return Message.tool(value.toolCallId(), value.toolName(), value.result());
        }
        String content = value.result().isTextual()
                ? value.result().asText() : value.result().toString();
        if (value.error()) {
            content = "TOOL_ERROR: " + content;
        }
        return Message.tool(value.toolCallId(), value.toolName(), content);
    }

    private List<ToolDefinition> tools(List<ModelToolDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> tools = new ArrayList<>(definitions.size());
        for (ModelToolDefinition definition : definitions) {
            tools.add(new ToolDefinition(
                    definition.name(), definition.description(), definition.inputSchema()));
        }
        return tools;
    }

    private StructuredOutput structuredOutput(ChatCompletionCommand command) {
        if (command.outputFormat() != ChatCompletionCommand.OutputFormat.JSON_OBJECT) {
            return StructuredOutput.none();
        }
        if (command.outputSchema() == null) {
            return StructuredOutput.jsonObject("response", false);
        }
        return StructuredOutput.jsonSchema("response", command.outputSchema(), false);
    }

    private ReasoningOptions reasoning(ModelConfiguration configuration) {
        if (providerType != ModelProviderType.GEMINI) {
            return ReasoningOptions.none();
        }
        GeminiThinkingProperties.ThinkingSetting override =
                geminiThinking.resolve(configuration.modelName());
        if (override != null) {
            if (override.budget() != null) {
                return new ReasoningOptions(null, override.budget(), true);
            }
            if ("minimal".equals(override.level())) {
                // AI Model Bridge 0.2.0 exposes low/medium/high; a one-token budget is the
                // closest equivalent for the existing minimal setting.
                return new ReasoningOptions(null, 1, true);
            }
            return new ReasoningOptions(override.level(), null, true);
        }
        if (configuration.capabilities().contains(ModelCapability.THINKING)) {
            return ReasoningOptions.none();
        }
        return new ReasoningOptions(null, null, false);
    }

    private Map<String, Object> providerOptions(ModelConfiguration configuration) {
        if (providerType != ModelProviderType.OPENAI_COMPATIBLE) {
            return Map.of();
        }
        return new LinkedHashMap<>(extensionParams.forModel(
                configuration.providerType(), configuration.modelName()));
    }

    private List<ModelToolCall> toolCalls(
            ModelConfiguration configuration, ModelTurnCommand command, List<ToolCall> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        int existing = 0;
        if (providerType == ModelProviderType.GEMINI) {
            for (ModelMessage message : command.messages()) {
                if (message instanceof ModelMessage.Assistant assistant) {
                    existing += assistant.toolCalls().size();
                }
            }
        }
        List<ModelToolCall> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            ToolCall call = source.get(index);
            String id = providerType == ModelProviderType.GEMINI
                    ? "gemini-call-" + (existing + index) : call.id();
            result.add(new ModelToolCall(
                    id, call.name(), call.arguments(), call.thoughtSignature()));
        }
        return result;
    }

    private void rejectTerminalFinish(FinishReason finish, TokenUsage usage) {
        if (finish == FinishReason.LENGTH) {
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                    "MODEL_BRIDGE / OUTPUT_TRUNCATED", usageInput(usage), usageOutput(usage));
        }
        if (finish == FinishReason.CONTENT_FILTER) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_REQUEST_REJECTED,
                    "模型因内容安全策略终止");
        }
        if (finish == FinishReason.ERROR) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
    }

    private BusinessException translate(ModelBridgeException failure) {
        Integer input = usageInput(failure.usage());
        Integer output = usageOutput(failure.usage());
        int status = failure.upstreamStatus() > 0
                ? failure.upstreamStatus() : failure.category().statusCode();
        return switch (failure.category()) {
            case LENGTH -> new ProviderResponseFailure(
                    ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                    "MODEL_BRIDGE / OUTPUT_TRUNCATED", input, output);
            case AUTHENTICATION -> new BusinessException(
                    ErrorCode.AI_MODEL_CREDENTIAL_INVALID,
                    "模型 API Key 无效或无权访问 (HTTP " + status + ")");
            case AUTHORIZATION -> new BusinessException(
                    ErrorCode.AI_PROVIDER_REQUEST_REJECTED,
                    "模型服务拒绝请求 (HTTP " + status + ")");
            case RATE_LIMIT -> new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
            case TIMEOUT -> new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
            case NETWORK_POLICY -> new BusinessException(
                    ErrorCode.AI_PROVIDER_REQUEST_REJECTED,
                    "模型端点不符合出站安全策略");
            case INVALID_RESPONSE, SCHEMA -> new ProviderResponseFailure(
                    ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "MODEL_BRIDGE / " + failure.category().name(), input, output);
            case BAD_REQUEST -> status == 404
                    ? new BusinessException(ErrorCode.AI_PROVIDER_NOT_FOUND)
                    : new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                            "模型服务返回 HTTP " + status);
            case UNAVAILABLE -> new BusinessException(
                    ErrorCode.AI_PROVIDER_ERROR, "模型服务返回 HTTP " + status);
            case UNSUPPORTED, INTERNAL -> new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        };
    }

    private static void awaitBounded(StreamHandle handle) {
        long deadline = System.nanoTime()
                + Duration.ofMillis(AiRequestDeadline.timeoutMillis(REQUEST_TIMEOUT)).toNanos();
        while (!handle.isDone()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                handle.cancel();
                throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
            }
            try {
                Thread.sleep(Math.min(50L, Math.max(1L, remaining / 1_000_000L)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                handle.cancel();
                throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
            }
        }
        handle.await();
    }

    private ChatCompletionResult result(
            ModelConfiguration configuration, String content, String responseModel,
            Integer inputTokens, Integer outputTokens, long started) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
        return new ChatCompletionResult(
                content.strip(),
                providerType.name(),
                responseModel == null || responseModel.isBlank()
                        ? configuration.modelName() : responseModel,
                inputTokens,
                outputTokens,
                Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
    }

    private static ModelUsage usage(TokenUsage usage) {
        if (usage == null || usage.isEmpty()) {
            return null;
        }
        return new ModelUsage(usage.inputTokens(), usage.outputTokens());
    }

    private static Integer usageInput(TokenUsage usage) {
        return usage == null ? null : usage.inputTokens();
    }

    private static Integer usageOutput(TokenUsage usage) {
        return usage == null ? null : usage.outputTokens();
    }

    private static ModelFinishReason finishReason(FinishReason finish) {
        if (finish == null) {
            return ModelFinishReason.UNKNOWN;
        }
        return switch (finish) {
            case STOP -> ModelFinishReason.STOP;
            case LENGTH -> ModelFinishReason.LENGTH;
            case TOOL_CALLS -> ModelFinishReason.TOOL_CALLS;
            case CONTENT_FILTER -> ModelFinishReason.CONTENT_FILTER;
            case ERROR -> ModelFinishReason.ERROR;
            case UNKNOWN -> ModelFinishReason.UNKNOWN;
        };
    }
}
