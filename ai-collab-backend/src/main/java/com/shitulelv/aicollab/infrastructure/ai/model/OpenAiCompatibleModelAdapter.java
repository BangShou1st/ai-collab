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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class OpenAiCompatibleModelAdapter extends AbstractModelProviderAdapter
        implements ModelTurnProviderAdapter {
    public OpenAiCompatibleModelAdapter(ObjectMapper mapper, JsonHttpModelClient http) {
        super(mapper, http);
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

    private ChatCompletionResult completeStreamingSync(
            ModelConfiguration config, String apiKey, ChatCompletionCommand command) {
        long started = System.nanoTime();
        StringBuilder content = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<String> model =
                new java.util.concurrent.atomic.AtomicReference<>(config.modelName());
        java.util.concurrent.atomic.AtomicReference<Integer> input = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Integer> output = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Exception> errorRef = new java.util.concurrent.atomic.AtomicReference<>();

        completeStream(config, apiKey, command,
                token -> content.append(token),
                result -> {
                    model.set(result.model());
                    input.set(result.promptTokens());
                    output.set(result.completionTokens());
                    latch.countDown();
                },
                error -> {
                    errorRef.set(error);
                    latch.countDown();
                });

        try {
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
        }

        if (errorRef.get() != null) {
            throw errorRef.get() instanceof BusinessException be
                    ? be : new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }

        return result(config, content.toString(), model.get(), input.get(), output.get(), started);
    }

    @Override
    public void completeStream(
            ModelConfiguration config, String apiKey, ChatCompletionCommand command,
            Consumer<String> onToken, Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError) {
        completeStreamWithSession(config, apiKey, command, null, null, onToken, onDone, onError);
    }

    /**
     * Zen preset structured output: prompt-forced JSON over streaming (spec-agent production shape).
     * Never sends response_format; caller forces JSON via prompt and validates strictly.
     */
    public ChatCompletionResult completeStreamingSyncWithSession(ModelConfiguration config, String apiKey,
            ChatCompletionCommand command, AiRequestMetadata metadata, String userAgent) {
        long started = System.nanoTime();
        StringBuilder content = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<String> model =
                new java.util.concurrent.atomic.AtomicReference<>(config.modelName());
        java.util.concurrent.atomic.AtomicReference<Integer> input = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Integer> output = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Exception> errorRef = new java.util.concurrent.atomic.AtomicReference<>();
        completeStreamWithSession(config, apiKey, command, metadata, userAgent,
                content::append,
                result -> {
                    model.set(result.model());
                    input.set(result.promptTokens());
                    output.set(result.completionTokens());
                },
                errorRef::set);
        if (errorRef.get() != null) {
            throw errorRef.get() instanceof BusinessException be
                    ? be : new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
        return result(config, content.toString(), model.get(), input.get(), output.get(), started);
    }

    public void completeStreamWithSession(
            ModelConfiguration config, String apiKey, ChatCompletionCommand command, AiRequestMetadata metadata, String userAgent,
            Consumer<String> onToken, Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError) {
        safeStream(() -> {
            long started = System.nanoTime();
            StringBuilder content = new StringBuilder();
            AtomicReference<String> model = new AtomicReference<>(config.modelName());
            AtomicReference<Integer> input = new AtomicReference<>();
            AtomicReference<Integer> output = new AtomicReference<>();
            AtomicBoolean terminal = new AtomicBoolean(false);
            AtomicReference<String> finishReason = new AtomicReference<>("missing");
            AtomicBoolean reasoningPresent = new AtomicBoolean(false);
            // userAgent != null marks the Zen preset path (Custom callers pass null):
            // Zen includes its client envelope; Custom keeps its own contract.
            http.stream(endpoint(config), metadata == null && userAgent == null ? headers(apiKey) : headersWithSession(apiKey, metadata, userAgent), userAgent == null ? request(config, command, true) : zenRequest(config, command, true), (event, data) -> {
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
                if (userAgent != null && choice.path("delta").path("tool_calls").isArray()
                        && !choice.path("delta").path("tool_calls").isEmpty())
                    throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                            "PROVIDER_STREAM / UNEXPECTED_TOOL_CALLS", input.get(), output.get());
                String token = choice.path("delta").path("content").asText("");
                if (!token.isEmpty()) {
                    content.append(token);
                    onToken.accept(token);
                }
            });
            if (terminal.compareAndSet(false, true)) {
                if (content.toString().isBlank()) throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "PROVIDER_STREAM / EMPTY_CONTENT / finish=" + finishReason.get()
                                + " / contentChars=" + content.length() + " / reasoningPresent=" + reasoningPresent.get(), input.get(), output.get());
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

            // 既没有文本也没有 tool_calls 是协议错误
            if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "模型既没有返回文本也没有返回工具调用",
                        usage == null ? null : usage.inputTokens(),
                        usage == null ? null : usage.outputTokens());
            }

            // finish_reason=length 表示截断
            if (finishReason == ModelFinishReason.LENGTH) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                        "PROVIDER / OUTPUT_TRUNCATED",
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

    private ModelTurnResult turnStreamingSync(
            ModelConfiguration config, String apiKey, ModelTurnCommand command) {
        long started = System.nanoTime();
        StringBuilder content = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<String> model =
                new java.util.concurrent.atomic.AtomicReference<>(config.modelName());
        java.util.concurrent.atomic.AtomicReference<ModelFinishReason> finishReason =
                new java.util.concurrent.atomic.AtomicReference<>(ModelFinishReason.UNKNOWN);
        java.util.concurrent.atomic.AtomicReference<ModelUsage> usage =
                new java.util.concurrent.atomic.AtomicReference<>();
        // delta.tool_calls 按 index 聚合：id / name / arguments 可能分片到达
        java.util.Map<Integer, DeltaToolCall> deltas = new java.util.TreeMap<>();

        // JsonHttpModelClient.stream() 本身同步读取 SSE 直到结束，返回时流已完成，
        // 不需要 CountDownLatch 等待。之前 latch 从未 countDown 会导致固定 60s 等待。
        http.stream(endpoint(config), headers(apiKey), turnRequest(config, command, true), (event, data) -> {
            if (data == null) return;
            if (data.hasNonNull("model")) model.set(data.path("model").asText());
            JsonNode usageNode = data.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                usage.set(new ModelUsage(
                        integer(usageNode.path("prompt_tokens")),
                        integer(usageNode.path("completion_tokens"))));
            }
            JsonNode choice = data.path("choices").path(0);
            if (choice.isMissingNode()) return;
            JsonNode delta = choice.path("delta");
            // 文本增量
            String token = delta.path("content").asText("");
            if (!token.isEmpty()) {
                content.append(token);
            }
            // 原生 tool_calls 增量聚合
            JsonNode toolCallsNode = delta.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode node : toolCallsNode) {
                    if (!node.path("index").isInt() || node.path("index").asInt()<0)
                        throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,"工具分片缺少有效 index");
                    int index = node.path("index").asInt();
                    DeltaToolCall acc = deltas.computeIfAbsent(index, k -> new DeltaToolCall());
                    String idFrag = node.path("id").asText("");
                    if (!idFrag.isEmpty() && !idFrag.contentEquals(acc.id)) acc.id.append(idFrag);
                    JsonNode fn = node.path("function");
                    String nameFrag = fn.path("name").asText("");
                    if (!nameFrag.isEmpty() && !nameFrag.contentEquals(acc.name)) acc.name.append(nameFrag);
                    String argsFrag = fn.path("arguments").asText("");
                    if (!argsFrag.isEmpty()) acc.arguments.append(argsFrag);
                }
            }
            // 解析 finish_reason
            String finish = choice.path("finish_reason").asText("");
            if (!finish.isEmpty()) {
                finishReason.set(mapFinishReason(finish));
            }
        });

        if (finishReason.get()==ModelFinishReason.LENGTH) throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        List<ModelToolCall> toolCalls = buildDeltaToolCallsCarryingUsage(deltas, usage);

        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);

        // 既没有文本也没有 tool_calls 是协议错误
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "模型既没有返回文本也没有返回工具调用");
        }

        // finish_reason=length 表示截断
        if (finishReason.get() == ModelFinishReason.LENGTH) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        }

        return new ModelTurnResult(
                content.isEmpty() ? null : content.toString(),
                toolCalls,
                finishReason.get(),
                usage.get(),
                providerType().name(),
                model.get(),
                latencyMs);
    }

    /** 工具参数解析在握有 usage 的边界包装：已收到的提供商用量随异常携带，错误码与消息保留。 */
    private List<ModelToolCall> buildDeltaToolCallsCarryingUsage(
            java.util.Map<Integer, DeltaToolCall> deltas, java.util.concurrent.atomic.AtomicReference<ModelUsage> usage) {
        try {
            return buildDeltaToolCalls(deltas);
        } catch (BusinessException e) {
            throw new ProviderResponseFailure(e.getErrorCode(), e.getMessage(),
                    carriedPrompt(usage), carriedCompletion(usage));
        }
    }

    private List<ModelToolCall> buildDeltaToolCalls(java.util.Map<Integer, DeltaToolCall> deltas) {
        List<ModelToolCall> calls = new ArrayList<>();
        for (java.util.Map.Entry<Integer, DeltaToolCall> entry : deltas.entrySet()) {
            DeltaToolCall acc = entry.getValue();
            String id = acc.id.toString();
            String name = acc.name.toString();
            String raw = acc.arguments.length() == 0 ? "{}" : acc.arguments.toString();
            if (id.isBlank() || name.isBlank()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "工具调用缺少 function.name");
            }
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

    private static final class DeltaToolCall {
        final StringBuilder id = new StringBuilder();
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
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
        // stream_options.include_usage 只在明确支持的 Zen preset 路径发送，避免改变所有 Custom 网关的 wire contract。
        if (stream && includeUsage) {
            body.putObject("stream_options").put("include_usage", true);
        }

        // 构建多轮消息
        ArrayNode messages = body.putArray("messages");
        for (ModelMessage message : command.messages()) {
            switch (message) {
                case ModelMessage.System m -> messages.addObject()
                        .put("role", "system").put("content", m.content());
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
        return body;
    }

    private static Map<String, String> headers(String apiKey) {
        return Map.of("Authorization", "Bearer " + apiKey);
    }

    /** Generic metadata headers. Preset-agnostic: any caller may attach correlation + identity. */
    public static Map<String, String> headersWithSession(String apiKey, AiRequestMetadata metadata, String userAgent) {
        var result = new java.util.LinkedHashMap<String,String>();
        if (apiKey != null && !apiKey.isBlank()) result.put("Authorization", "Bearer " + apiKey.strip());
        if (metadata != null) result.put(OpenCodeZenTransport.SESSION_HEADER, metadata.correlationSessionId());
        if (userAgent != null) {
            result.put("User-Agent", userAgent);
            result.put(OpenCodeZenTransport.SESSION_HEADER, ZenClientIds.session(metadata == null ? null : metadata.correlationSessionId()));
            result.put("x-opencode-client", "cli");
            result.put("x-opencode-request", ZenClientIds.request());
            result.put("x-opencode-project", "global");
        }
        return result;
    }

    /**
     * Zen production wire: model / messages / stream plus the Free Tier client envelope.
     * Never send temperature, max_tokens, max_completion_tokens, response_format,
     * stream_options, reasoning_effort, thinking. Application token budget is
     * enforced locally and is distinct from provider wire max_tokens.
     * Agent native tool calls may additionally send tools / tool_choice / tool history.
     */
    private ObjectNode zenTurnRequest(ModelConfiguration config, ModelTurnCommand command, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.modelName());
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        for (ModelMessage message : command.messages()) {
            switch (message) {
                case ModelMessage.System m -> messages.addObject()
                        .put("role", "system").put("content", m.content());
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
            }
        }
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
            if (command.toolsRequired()) {
                body.put("tool_choice", "required");
            } else {
                body.put("tool_choice", "auto");
            }
        }
        addZenReservedTools(body);
        if (command.tools().isEmpty()) body.put("tool_choice", "none");
        return body;
    }

    private void addZenReservedTools(ObjectNode body) {
        ArrayNode tools = body.withArray("tools");
        for (JsonNode tool : tools) {
            if (isZenReservedTool(tool.path("function").path("name").asText()))
                throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "业务工具与 Zen 传输保留名称冲突");
        }
        for (String name : List.of("bash", "read")) {
            ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
            fn.put("name", name).put("description", "Reserved by the transport. Never call this tool.");
            fn.putObject("parameters").put("type", "object").putObject("properties")
                    .putObject(name.equals("bash") ? "command" : "filePath").put("type", "string");
        }
    }

    private static boolean isZenReservedTool(String name) {
        return "bash".equals(name) || "read".equals(name);
    }

    private ObjectNode zenRequest(ModelConfiguration config, ChatCompletionCommand command, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.modelName());
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", command.systemPrompt());
        messages.addObject().put("role", "user").put("content", command.userPrompt());
        if (!command.tools().isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (var tool : command.tools()) {
                ObjectNode toolObj = toolsArray.addObject();
                toolObj.put("type", "function");
                ObjectNode function = toolObj.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.inputSchema());
            }
        }
        addZenReservedTools(body);
        if (command.tools().isEmpty()) body.put("tool_choice", "none");
        return body;
    }

    public ChatCompletionResult completeWithSession(ModelConfiguration config, String apiKey,
            com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand command, AiRequestMetadata metadata, String userAgent) {
        long started = System.nanoTime();
        JsonNode response = http.post(endpoint(config), headersWithSession(apiKey, metadata, userAgent), zenRequest(config, command, false));
        JsonNode choice = response.path("choices").path(0);
        JsonNode usage = response.path("usage");
        if ("length".equals(choice.path("finish_reason").asText())) {
            // 提供商已上报用量随异常携带，结算时保留真实值，不被估算覆盖
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                    "PROVIDER_STREAM / OUTPUT_TRUNCATED",
                    integer(usage.path("prompt_tokens")), integer(usage.path("completion_tokens")));
        }
        return result(config, choice.path("message").path("content").asText(null),
                response.path("model").asText(config.modelName()),
                integer(usage.path("prompt_tokens")), integer(usage.path("completion_tokens")), started);
    }

    public ModelTurnResult turnWithSession(ModelConfiguration config, String apiKey, ModelTurnCommand command,
            AiRequestMetadata metadata, String userAgent) {
        return turnStreamingSyncWithSession(config, apiKey, command, metadata, userAgent);
    }

    private ModelTurnResult turnStreamingSyncWithSession(ModelConfiguration config, String apiKey,
            ModelTurnCommand command, AiRequestMetadata metadata, String userAgent) {
        long started = System.nanoTime();
        StringBuilder content = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<String> model =
                new java.util.concurrent.atomic.AtomicReference<>(config.modelName());
        java.util.concurrent.atomic.AtomicReference<ModelFinishReason> finishReason =
                new java.util.concurrent.atomic.AtomicReference<>(ModelFinishReason.UNKNOWN);
        java.util.concurrent.atomic.AtomicReference<ModelUsage> usage =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.Map<Integer, DeltaToolCall> deltas = new java.util.TreeMap<>();
        // 读流回调在 HttpClient 的读流线程上执行,ThreadLocal 观察者不可见:
        // 在发起请求的 worker 线程先捕获,回调线程用捕获值显式推送
        ModelContentPreview.Observer contentObserver = ModelContentPreview.capture();
        http.stream(endpoint(config), headersWithSession(apiKey, metadata, userAgent), zenTurnRequest(config, command, true), (event, data) -> {
            if (data == null) return;
            if (data.hasNonNull("model")) model.set(data.path("model").asText());
            JsonNode usageNode = data.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                usage.set(new ModelUsage(integer(usageNode.path("prompt_tokens")), integer(usageNode.path("completion_tokens"))));
            }
            JsonNode choice = data.path("choices").path(0);
            if (choice.isMissingNode()) return;
            String token = choice.path("delta").path("content").asText("");
            if (!token.isEmpty()) {
                content.append(token);
                ModelContentPreview.push(contentObserver, content.toString());
            }
            JsonNode toolCallsNode = choice.path("delta").path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode node : toolCallsNode) {
                    if (!node.path("index").isInt() || node.path("index").asInt() < 0)
                        throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,"工具分片缺少有效 index",
                                carriedPrompt(usage), carriedCompletion(usage));
                    int index = node.path("index").asInt();
                    DeltaToolCall acc = deltas.computeIfAbsent(index, k -> new DeltaToolCall());
                    String idFrag = node.path("id").asText("");
                    if (!idFrag.isEmpty() && !idFrag.contentEquals(acc.id)) acc.id.append(idFrag);
                    String nameFrag = node.path("function").path("name").asText("");
                    if (!nameFrag.isEmpty() && !nameFrag.contentEquals(acc.name)) acc.name.append(nameFrag);
                    String argsFrag = node.path("function").path("arguments").asText("");
                    if (!argsFrag.isEmpty()) acc.arguments.append(argsFrag);
                }
            }
            String finish = choice.path("finish_reason").asText("");
            if (!finish.isEmpty()) finishReason.set(mapFinishReason(finish));
        });
        List<ModelToolCall> toolCalls = buildDeltaToolCallsCarryingUsage(deltas, usage);
        if (!content.isEmpty()) ModelContentPreview.finish(contentObserver, content.toString());
        if (toolCalls.stream().anyMatch(call -> isZenReservedTool(call.name())))
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "模型调用了 Zen 传输保留工具；该调用不会执行",
                    carriedPrompt(usage), carriedCompletion(usage));
        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "model returned neither text nor tool calls",
                    carriedPrompt(usage), carriedCompletion(usage));
        }
        if (finishReason.get() == ModelFinishReason.LENGTH) {
            // 提供商已上报用量随异常携带（流式 usage 在收尾块到达），结算时保留真实值
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED,
                    "PROVIDER_STREAM / OUTPUT_TRUNCATED", carriedPrompt(usage), carriedCompletion(usage));
        }
        return new ModelTurnResult(content.isEmpty() ? null : content.toString(), toolCalls, finishReason.get(),
                usage.get(), providerType().name(), model.get(), latencyMs);
    }

    private Integer carriedPrompt(java.util.concurrent.atomic.AtomicReference<ModelUsage> usage) {
        ModelUsage u = usage.get();
        return u == null ? null : u.inputTokens();
    }

    private Integer carriedCompletion(java.util.concurrent.atomic.AtomicReference<ModelUsage> usage) {
        ModelUsage u = usage.get();
        return u == null ? null : u.outputTokens();
    }

    private ModelTurnResult parseTurnResponse(ModelConfiguration config, JsonNode response, long started) {
        JsonNode choice = response.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.has("content") && !message.get("content").isNull() ? message.path("content").asText(null) : null;
        List<ModelToolCall> toolCalls = parseToolCalls(message);
        ModelFinishReason fr = mapFinishReason(choice.path("finish_reason").asText(""));
        JsonNode usageNode = response.path("usage");
        ModelUsage usage = null;
        if (!usageNode.isMissingNode() && !usageNode.isNull()) usage = new ModelUsage(integer(usageNode.path("prompt_tokens")), integer(usageNode.path("completion_tokens")));
        if ((content == null || content.isBlank()) && toolCalls.isEmpty())
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE, "PROVIDER / EMPTY_RESPONSE",
                    usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens());
        if (fr == ModelFinishReason.LENGTH)
            throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED, "PROVIDER / OUTPUT_TRUNCATED",
                    usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens());
        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return new ModelTurnResult(content, toolCalls, fr, usage, providerType().name(), response.path("model").asText(config.modelName()), latencyMs);
    }
}
