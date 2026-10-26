package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Kilo Gateway Free 预设运行时。唯一一处把预设行分派到共享库客户端的地方：
 * 端点与模型策略来自 {@link ProviderPresetRegistry}，HTTP/SSE/免费模型判定由共享库负责，
 * 这里只负责把统一轮次合同映射成网关 OpenAI 兼容 wire，并把网关 JSON 映射回合同。
 *
 * <p>免费模型匿名调用不发送 Authorization，因此没有凭证与会话信封。规划可附带网关
 * 支持的 JSON Schema 响应格式；应用仍会执行完整的解析和业务校验。</p>
 */
@Component
public class KiloGatewayModelExecution {
    private static final ProviderPresetCode CODE = ProviderPresetCode.KILO_GATEWAY_FREE;
    private final ProviderPresetRegistry registry;
    private final ObjectMapper mapper;
    private final KiloGatewayTransport transport;

    @org.springframework.beans.factory.annotation.Autowired
    public KiloGatewayModelExecution(ProviderPresetRegistry registry, ObjectMapper mapper,
            KiloGatewayTransport transport) {
        this.registry = registry;
        this.mapper = mapper;
        this.transport = transport;
    }

    /** 无 Spring 上下文时的最小可执行实例（测试与默认构造使用）：仅构造客户端，不发起网络请求。 */
    public static KiloGatewayModelExecution standalone() {
        return new KiloGatewayModelExecution(new ProviderPresetRegistry(), new ObjectMapper(),
                new KiloGatewayClientTransport(ModelTransportConfiguration.configuredKiloClient(
                        new com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties(), new ObjectMapper())));
    }

    public boolean isKilo(UserAiProvider provider) {
        return provider != null && CODE.name().equals(provider.presetCode());
    }

    public ModelConfiguration runtimeConfig(UserAiProvider provider) {
        ProviderPresetRegistry.PresetPolicy policy = registry.require(CODE);
        return new ModelConfiguration(provider.id(), provider.userId(), policy.displayName(), policy.protocol(),
                policy.baseUrl(), policy.completionPath(), provider.encryptedApiKey(), provider.modelName(),
                provider.enabled(), provider.temperature(), provider.maxOutputTokens(), policy.capabilities(),
                provider.createdAt(), provider.updatedAt());
    }

    /** Agent 轮次：网关非流式返回完整 message（含原生 tool_calls）。 */
    public ModelTurnResult turn(UserAiProvider provider, ModelTurnCommand command) {
        long started = System.nanoTime();
        ModelConfiguration config = runtimeConfig(provider);
        JsonNode response = transport.chat(turnRequest(config, command));
        return parseTurn(config, response, started);
    }

    public ChatCompletionResult complete(UserAiProvider provider, ChatCompletionCommand command) {
        long started = System.nanoTime();
        ModelConfiguration config = runtimeConfig(provider);
        // 所有结构化结果均由应用解析校验；只有显式传入 schema 时才启用网关原生 Schema 模式。
        ChatCompletionCommand effective;
        if (command.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT
                && command.outputSchema() != null) {
            ChatCompletionCommand forced = ZenModelExecution.forceJson(command);
            effective = new ChatCompletionCommand(forced.projectId(), forced.systemPrompt(), forced.userPrompt(),
                    ChatCompletionCommand.OutputFormat.JSON_OBJECT, forced.purpose(), command.outputSchema(),
                    forced.tools(), forced.callerUserId());
        } else {
            effective = command.outputFormat() != ChatCompletionCommand.OutputFormat.TEXT
                    ? ZenModelExecution.forceJson(command) : command;
        }
        JsonNode response = transport.chat(completionRequest(config, effective, false));
        return parseCompletion(config, response, started);
    }

    /**
     * 流式完成语义由共享库负责：只有在观察到上游终止哨兵 {@code [DONE]} 时
     * {@code streamChat} 才正常返回；EOF 早于哨兵、错误帧或不可解析帧都会抛出异常。
     * 因此本方法只在正常返回后调用 {@code onDone}，成功与失败回调互斥。
     */
    public void completeStream(UserAiProvider provider, ChatCompletionCommand command,
            Consumer<String> onToken, Consumer<ChatCompletionResult> onDone, Consumer<Exception> onError) {
        try {
            ModelConfiguration config = runtimeConfig(provider);
            long started = System.nanoTime();
            StringBuilder content = new StringBuilder();
            AtomicReference<String> model = new AtomicReference<>(config.modelName());
            AtomicReference<Integer> input = new AtomicReference<>();
            AtomicReference<Integer> output = new AtomicReference<>();
            AtomicBoolean truncated = new AtomicBoolean(false);
            transport.streamChat(completionRequest(config, command, true), data -> {
                if (data == null) return;
                if (data.hasNonNull("model")) model.set(data.path("model").asText());
                JsonNode usage = data.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    input.set(integer(usage.path("prompt_tokens")));
                    output.set(integer(usage.path("completion_tokens")));
                }
                JsonNode choice = data.path("choices").path(0);
                if ("length".equals(choice.path("finish_reason").asText(""))) truncated.set(true);
                String token = choice.path("delta").path("content").asText("");
                if (!token.isEmpty()) {
                    content.append(token);
                    onToken.accept(token);
                }
            });
            if (truncated.get()) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                        "PROVIDER_STREAM / OUTPUT_TRUNCATED", input.get(), output.get());
            }
            if (content.toString().isBlank()) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "PROVIDER_STREAM / EMPTY_CONTENT", input.get(), output.get());
            }
            onDone.accept(new ChatCompletionResult(content.toString().strip(),
                    ModelProviderType.OPENAI_COMPATIBLE.name(), model.get(), input.get(), output.get(),
                    Math.max(0L, (System.nanoTime() - started) / 1_000_000L)));
        } catch (Exception failure) {
            onError.accept(failure instanceof BusinessException be
                    ? be : new BusinessException(ErrorCode.AI_PROVIDER_ERROR));
        }
    }

    // ========== 请求构建（网关 OpenAI 兼容 wire）==========

    private ObjectNode turnRequest(ModelConfiguration config, ModelTurnCommand command) {
        // Agent 轮次走非流式完整响应（共享库 chat() 拒绝 stream=true）。
        ObjectNode body = baseBody(config, false);
        ArrayNode messages = body.putArray("messages");
        for (ModelMessage message : command.messages()) {
            appendMessage(messages, message);
        }
        appendTools(body, command.tools(), command.toolsRequired(), true);
        return body;
    }

    private ObjectNode completionRequest(ModelConfiguration config, ChatCompletionCommand command, boolean stream) {
        ObjectNode body = baseBody(config, stream);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", command.systemPrompt());
        messages.addObject().put("role", "user").put("content", command.userPrompt());
        if (command.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT
                && command.outputSchema() != null) {
            ObjectNode responseFormat = body.putObject("response_format");
            responseFormat.put("type", "json_schema");
            ObjectNode schema = responseFormat.putObject("json_schema");
            schema.put("name", "planning_output");
            schema.put("strict", true);
            schema.set("schema", command.outputSchema());
        }
        appendTools(body, command.tools(), false, false);
        return body;
    }

    private ObjectNode baseBody(ModelConfiguration config, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.modelName());
        body.put("temperature", config.temperature());
        body.put("max_tokens", AiRequestOutputCap.effective(config.maxOutputTokens()));
        body.put("stream", stream);
        return body;
    }

    private void appendMessage(ArrayNode messages, ModelMessage message) {
        switch (message) {
            case ModelMessage.System m -> messages.addObject().put("role", "system").put("content", m.content());
            case ModelMessage.User m -> messages.addObject().put("role", "user").put("content", m.content());
            case ModelMessage.Assistant m -> {
                ObjectNode node = messages.addObject().put("role", "assistant");
                if (!m.content().isBlank()) node.put("content", m.content());
                if (!m.toolCalls().isEmpty()) {
                    ArrayNode calls = node.putArray("tool_calls");
                    for (ModelToolCall call : m.toolCalls()) {
                        ObjectNode function = calls.addObject()
                                .put("id", call.id())
                                .put("type", "function")
                                .putObject("function");
                        function.put("name", call.name());
                        function.put("arguments", call.arguments().toString());
                    }
                }
            }
            case ModelMessage.ToolResult m -> messages.addObject()
                    .put("role", "tool")
                    .put("tool_call_id", m.toolCallId())
                    .put("name", m.toolName())
                    .put("content", m.result().toString());
        }
    }

    private void appendTools(ObjectNode body, List<ModelToolDefinition> tools, boolean required, boolean hasHistory) {
        if (tools.isEmpty()) {
            if (hasHistory) body.put("tool_choice", "none");
            return;
        }
        ArrayNode array = body.putArray("tools");
        for (ModelToolDefinition tool : tools) {
            ObjectNode function = array.addObject().put("type", "function").putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.inputSchema());
        }
        body.put("tool_choice", required ? "required" : "auto");
    }

    // ========== 响应解析 ==========

    private ModelTurnResult parseTurn(ModelConfiguration config, JsonNode response, long started) {
        JsonNode choice = response.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.has("content") && !message.get("content").isNull()
                ? message.path("content").asText(null) : null;
        List<ModelToolCall> toolCalls = parseToolCalls(message);
        ModelFinishReason finish = mapFinishReason(choice.path("finish_reason").asText(""));
        ModelUsage usage = usage(response);
        if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "PROVIDER / EMPTY_RESPONSE", carriedPrompt(usage), carriedCompletion(usage));
        }
        if (finish == ModelFinishReason.LENGTH) {
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                    "PROVIDER / OUTPUT_TRUNCATED", carriedPrompt(usage), carriedCompletion(usage));
        }
        return new ModelTurnResult(content, toolCalls, finish, usage,
                ModelProviderType.OPENAI_COMPATIBLE.name(),
                response.path("model").asText(config.modelName()),
                Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
    }

    private ChatCompletionResult parseCompletion(ModelConfiguration config, JsonNode response, long started) {
        JsonNode choice = response.path("choices").path(0);
        ModelUsage usage = usage(response);
        if ("length".equals(choice.path("finish_reason").asText(""))) {
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                    "PROVIDER / OUTPUT_TRUNCATED", carriedPrompt(usage), carriedCompletion(usage));
        }
        String content = choice.path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "PROVIDER / EMPTY_CONTENT", carriedPrompt(usage), carriedCompletion(usage));
        }
        return new ChatCompletionResult(content.strip(), ModelProviderType.OPENAI_COMPATIBLE.name(),
                response.path("model").asText(config.modelName()),
                carriedPrompt(usage), carriedCompletion(usage),
                Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
    }

    private static ModelUsage usage(JsonNode response) {
        JsonNode node = response.path("usage");
        if (node.isMissingNode() || node.isNull()) return null;
        return new ModelUsage(integer(node.path("prompt_tokens")), integer(node.path("completion_tokens")));
    }

    private List<ModelToolCall> parseToolCalls(JsonNode message) {
        JsonNode node = message.path("tool_calls");
        if (node.isMissingNode() || !node.isArray()) return List.of();
        List<ModelToolCall> calls = new ArrayList<>();
        for (JsonNode call : node) {
            String id = call.path("id").asText();
            String name = call.path("function").path("name").asText();
            String raw = call.path("function").path("arguments").asText("{}");
            JsonNode args;
            try {
                args = mapper.reader()
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(raw);
            } catch (Exception invalid) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "工具调用参数不是合法 JSON", null, null);
            }
            if (!args.isObject()) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "工具调用参数必须是 JSON Object", null, null);
            }
            try {
                calls.add(new ModelToolCall(id, name, args));
            } catch (IllegalArgumentException invalid) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "工具调用缺少身份或名称", null, null);
            }
        }
        return calls;
    }

    private static ModelFinishReason mapFinishReason(String reason) {
        if (reason == null) return ModelFinishReason.UNKNOWN;
        return switch (reason) {
            case "stop" -> ModelFinishReason.STOP;
            case "tool_calls" -> ModelFinishReason.TOOL_CALLS;
            case "length" -> ModelFinishReason.LENGTH;
            case "content_filter" -> ModelFinishReason.CONTENT_FILTER;
            default -> ModelFinishReason.UNKNOWN;
        };
    }

    private static Integer carriedPrompt(ModelUsage usage) {
        return usage == null ? null : usage.inputTokens();
    }

    private static Integer carriedCompletion(ModelUsage usage) {
        return usage == null ? null : usage.outputTokens();
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
    }
}
