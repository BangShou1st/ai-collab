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
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class OpenAiCompatibleModelAdapter extends AbstractModelProviderAdapter
        implements ModelTurnProviderAdapter {
    private final ProviderExtensionParamsProperties extensionParams;

    public OpenAiCompatibleModelAdapter(ObjectMapper mapper, JsonHttpModelClient http) {
        this(mapper, http, ProviderExtensionParamsProperties.disabled());
    }

    @Autowired
    public OpenAiCompatibleModelAdapter(ObjectMapper mapper, JsonHttpModelClient http,
            ProviderExtensionParamsProperties extensionParams) {
        super(mapper, http);
        this.extensionParams = extensionParams == null
                ? ProviderExtensionParamsProperties.disabled() : extensionParams;
    }

    @Override
    public ModelProviderType providerType() {
        return ModelProviderType.OPENAI_COMPATIBLE;
    }

    // ========== 旧接口保持不变 ==========

    @Override
    public ChatCompletionResult complete(
            ModelConfiguration config, String apiKey, ChatCompletionCommand command) {
        long started = System.nanoTime();
        try {
            JsonNode response = http.post(endpoint(config), headers(apiKey), request(config, command, false));
            JsonNode choice = response.path("choices").path(0);
            JsonNode usage = response.path("usage");
            String finish = choice.path("finish_reason").asText("");
            String safeFinish = List.of("stop", "length", "tool_calls", "content_filter").contains(finish)
                    ? finish : "other_or_missing";
            JsonNode content = choice.path("message").path("content");
            String category = "length".equals(finish) ? "OUTPUT_TRUNCATED"
                    : !choice.isObject() ? "MISSING_CHOICE"
                    : !content.isTextual() ? "MISSING_OR_NON_TEXT_CONTENT"
                    : content.asText().isBlank() ? "EMPTY_CONTENT" : null;
            if (category != null) {
                throw new ProviderResponseFailure("length".equals(finish)
                        ? ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED : ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "PROVIDER_RESPONSE / " + category + " / finish=" + safeFinish
                                + " / contentChars=" + (content.isTextual() ? content.asText().length() : 0)
                                + " / reasoningPresent=" + choice.path("message").hasNonNull("reasoning_content"),
                        integer(usage.path("prompt_tokens")), integer(usage.path("completion_tokens")));
            }
            return result(config, choice.path("message").path("content").asText(null),
                    response.path("model").asText(config.modelName()),
                    integer(usage.path("prompt_tokens")), integer(usage.path("completion_tokens")), started);
        } catch (BusinessException e) {
            throw e;
        }
    }

    @Override
    public void completeStream(
            ModelConfiguration config, String apiKey, ChatCompletionCommand command,
            Consumer<String> onToken, Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError) {
        safeStream(() -> {
            long started = System.nanoTime();
            StringBuilder content = new StringBuilder();
            java.util.concurrent.atomic.AtomicReference<String> model =
                    new java.util.concurrent.atomic.AtomicReference<>(config.modelName());
            java.util.concurrent.atomic.AtomicReference<Integer> input =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Integer> output =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean terminal =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicReference<String> finishReason =
                    new java.util.concurrent.atomic.AtomicReference<>("missing");
            java.util.concurrent.atomic.AtomicBoolean reasoningPresent =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            ModelContentPreview.Observer contentObserver = ModelContentPreview.capture();
            java.util.function.BiConsumer<String, JsonNode> eventHandler = (event, data) -> {
                if (data == null) return;
                if (data.hasNonNull("model")) model.set(data.path("model").asText());
                JsonNode usage = data.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    input.set(integer(usage.path("prompt_tokens")));
                    output.set(integer(usage.path("completion_tokens")));
                }
                JsonNode choice = data.path("choices").path(0);
                String finish = choice.path("finish_reason").asText("");
                if (!finish.isEmpty()) finishReason.set(List.of("stop", "length", "tool_calls", "content_filter").contains(finish) ? finish : "other");
                if (choice.path("delta").hasNonNull("reasoning_content")) reasoningPresent.set(true);
                if ("length".equals(finish)) {
                    terminal.set(true);
                    throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                            "PROVIDER_STREAM / OUTPUT_TRUNCATED / finish=length / contentChars=" + content.length(), input.get(), output.get());
                }
                String token = choice.path("delta").path("content").asText("");
                if (!token.isEmpty()) {
                    content.append(token);
                    onToken.accept(token);
                    ModelContentPreview.push(contentObserver, content.toString());
                }
            };
            http.stream(endpoint(config), headers(apiKey), request(config, command, true), eventHandler);
            if (terminal.compareAndSet(false, true)) {
                if (content.toString().isBlank()) throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "PROVIDER_STREAM / EMPTY_CONTENT / finish=" + finishReason.get()
                                + " / contentChars=" + content.length() + " / reasoningPresent=" + reasoningPresent.get(), input.get(), output.get());
                ModelContentPreview.finish(contentObserver, content.toString());
                onDone.accept(result(config, content.toString(), model.get(), input.get(), output.get(), started));
            }
        }, onError);
    }

    // ========== 新接口：原生 Tool Calling ==========

    @Override
    public ModelTurnResult turn(ModelConfiguration config, String apiKey, ModelTurnCommand command) {
        long started = System.nanoTime();
        org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleModelAdapter.class)
                .info("Turning with model: {}, endpoint: {}, stream: false",
                        config.modelName(), endpoint(config));
        try {
            JsonNode response = http.post(endpoint(config), headers(apiKey), turnRequest(config, command));
            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");

            // 解析文本
            String content = message.has("content") && !message.get("content").isNull()
                    ? message.path("content").asText(null) : null;

            // 解析 tool_calls
            List<ModelToolCall> toolCalls = parseToolCalls(message);

            // 解析 finish_reason
            ModelFinishReason finishReason = mapFinishReason(choice.path("finish_reason").asText(""));

            // 解析 usage
            JsonNode usageNode = response.path("usage");
            ModelUsage usage = null;
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                usage = new ModelUsage(
                        integer(usageNode.path("prompt_tokens")),
                        integer(usageNode.path("completion_tokens")));
            }

            String responseModel = response.path("model").asText(config.modelName());
            long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);

            // finish_reason=length 表示截断（优先判定，即便 content 为空也是截断而不是协议格式错误）
            if (finishReason == ModelFinishReason.LENGTH) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                        "PROVIDER / OUTPUT_TRUNCATED",
                        usage == null ? null : usage.inputTokens(),
                        usage == null ? null : usage.outputTokens());
            }

            // 既没有文本也没有 tool_calls 是协议错误
            if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "模型既没有返回文本也没有返回工具调用",
                        usage == null ? null : usage.inputTokens(),
                        usage == null ? null : usage.outputTokens());
            }

            return new ModelTurnResult(
                    content, toolCalls, finishReason, usage,
                    providerType().name(), responseModel, latencyMs);
        } catch (BusinessException e) {
            throw e;
        }
    }

    // ========== 请求构建 ==========

    private ObjectNode turnRequest(ModelConfiguration config, ModelTurnCommand command) {
        return turnRequest(config, command, false);
    }

    private ObjectNode turnRequest(ModelConfiguration config, ModelTurnCommand command, boolean stream) {
        return turnRequest(config, command, stream, false);
    }

    private ObjectNode turnRequest(ModelConfiguration config, ModelTurnCommand command, boolean stream, boolean includeUsage) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.modelName());
        body.put("temperature", config.temperature());
        // 本次请求的有效单次输出上限：默认取配置值，运行因本次窗口更小而派生的封顶
        // 只作用于内存中的请求参数（不改用户持久设置），与窗口计算使用同一份快照。
        body.put("max_tokens", AiRequestOutputCap.effective(config.maxOutputTokens()));
        body.put("stream", stream);
        // stream_options.include_usage only when explicitly requested by the streaming caller.
        if (stream && includeUsage) {
            body.putObject("stream_options").put("include_usage", true);
        }

        // 构建多轮消息：系统提示与系统指令统一置于最前，避免在 tool 结果后出现 system 导致模型角色混淆
        ArrayNode messages = body.putArray("messages");
        List<ModelMessage> nonSystemMessages = new ArrayList<>();
        for (ModelMessage message : command.messages()) {
            if (message instanceof ModelMessage.System m) {
                messages.addObject().put("role", "system").put("content", m.content());
            } else {
                nonSystemMessages.add(message);
            }
        }
        for (ModelMessage message : nonSystemMessages) {
            switch (message) {
                case ModelMessage.User m -> messages.addObject()
                        .put("role", "user").put("content", m.content());
                case ModelMessage.Assistant m -> {
                    ObjectNode node = messages.addObject().put("role", "assistant");
                    if (!m.content().isBlank()) {
                        node.put("content", m.content());
                    }
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
                default -> {}
            }
        }

        // 添加工具定义
        if (!command.tools().isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ModelToolDefinition tool : command.tools()) {
                ObjectNode toolObj = toolsArray.addObject();
                toolObj.put("type", "function");
                ObjectNode function = toolObj.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.inputSchema());
            }
            // tool_choice
            if (command.toolsRequired()) {
                body.put("tool_choice", "required");
            } else {
                body.put("tool_choice", "auto");
            }
        }
        applyProviderExtensionParams(body, config);
        return body;
    }

    // ========== 响应解析 ==========

    private List<ModelToolCall> parseToolCalls(JsonNode message) {
        List<ModelToolCall> calls = new ArrayList<>();
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isMissingNode() || !toolCallsNode.isArray()) {
            return calls;
        }
        for (JsonNode node : toolCallsNode) {
            String id = node.path("id").asText();
            String name = node.path("function").path("name").asText();
            if (id.isBlank() || name.isBlank()) throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,"工具调用缺少身份或名称");
            String raw = node.path("function").path("arguments").asText("{}");
            JsonNode args;
            try {
                args = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "工具调用参数不是合法 JSON");
            }
            if (!args.isObject()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "工具调用参数必须是 JSON Object");
            }
            calls.add(new ModelToolCall(id, name, args));
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

    // ========== 旧请求构建（保持不变）==========

    private ObjectNode request(
            ModelConfiguration config, ChatCompletionCommand command, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.modelName());
        body.put("temperature", config.temperature());
        // 本次请求的有效单次输出上限：默认取配置值，运行因本次窗口更小而派生的封顶
        // 只作用于内存中的请求参数（不改用户持久设置），与窗口计算使用同一份快照。
        body.put("max_tokens", AiRequestOutputCap.effective(config.maxOutputTokens()));
        body.put("stream", stream);
        if (stream) {
            body.putObject("stream_options").put("include_usage", true);
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", command.systemPrompt());
        messages.addObject().put("role", "user").put("content", command.userPrompt());
        if (command.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT) {
            ObjectNode format = body.putObject("response_format");
            if (command.outputSchema() == null) {
                format.put("type", "json_object");
            } else {
                format.put("type", "json_schema");
                ObjectNode schema = format.putObject("json_schema");
                schema.put("name", "response");
                schema.put("strict", true);
                schema.set("schema", command.outputSchema());
            }
        }
        if (!command.tools().isEmpty()) {
            addCommonTools(body.putArray("tools"), command, (target, tool) -> {
                target.put("type", "function");
                ObjectNode function = target.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.inputSchema());
            });
        }
        applyProviderExtensionParams(body, config);
        return body;
    }

    /**
     * 受控 provider 扩展参数（D1）：只追加白名单内的标量字段，不改 messages/response_format/tools。
     *
     * <p>开关关闭、未配置该模型、或 provider 不是 OPENAI_COMPATIBLE 时无任何写入，
     * 请求体与接入前逐字节一致。</p>
     *
     * <p>本方法同时挂在 {@code request()}（complete / completeStream）与
     * {@code turnRequest()}（原生 Tool Calling）两条构建路径上。</p>
     */
    private void applyProviderExtensionParams(ObjectNode body, ModelConfiguration config) {
        Map<String, Object> params = extensionParams.forModel(config.providerType(), config.modelName());
        params.forEach((key, value) -> body.set(key, mapper.valueToTree(value)));
    }

    private static Map<String, String> headers(String apiKey) {
        return Map.of("Authorization", "Bearer " + apiKey);
    }

}
