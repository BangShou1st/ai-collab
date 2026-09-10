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
            if ("length".equals(choice.path("finish_reason").asText())) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
            }
            JsonNode usage = response.path("usage");
            return result(config, choice.path("message").path("content").asText(null),
                    response.path("model").asText(config.modelName()),
                    integer(usage.path("prompt_tokens")), integer(usage.path("completion_tokens")), started);
        } catch (BusinessException e) {
            // 如果非流式请求失败且模型支持流式，自动降级到流式模式
            if (e.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR
                    && config.capabilities().contains(ModelCapability.STREAMING)) {
                org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleModelAdapter.class)
                        .warn("Non-streaming request failed for model {}, retrying with streaming",
                                config.modelName());
                return completeStreamingSync(config, apiKey, command);
            }
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
            http.stream(endpoint(config), metadata == null ? headers(apiKey) : headersWithSession(apiKey, metadata, userAgent), request(config, command, true), (event, data) -> {
                if (data == null) return;
                if (data.hasNonNull("model")) model.set(data.path("model").asText());
                JsonNode usage = data.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    input.set(integer(usage.path("prompt_tokens")));
                    output.set(integer(usage.path("completion_tokens")));
                }
                JsonNode choice = data.path("choices").path(0);
                String finish = choice.path("finish_reason").asText("");
                if ("length".equals(finish)) {
                    terminal.set(true);
                    throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
                }
                String token = choice.path("delta").path("content").asText("");
                if (!token.isEmpty()) {
                    content.append(token);
                    onToken.accept(token);
                }
            });
            if (terminal.compareAndSet(false, true)) {
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
                throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "模型既没有返回文本也没有返回工具调用");
            }

            // finish_reason=length 表示截断
            if (finishReason == ModelFinishReason.LENGTH) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
            }

            return new ModelTurnResult(
                    content, toolCalls, finishReason, usage,
                    providerType().name(), responseModel, latencyMs);
        } catch (BusinessException e) {
            // 如果非流式请求失败且模型支持流式，自动降级到流式模式
            if (e.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR
                    && config.capabilities().contains(ModelCapability.STREAMING)) {
                org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleModelAdapter.class)
                        .warn("Non-streaming turn request failed for model {}, retrying with streaming",
                                config.modelName());
                return turnStreamingSync(config, apiKey, command);
            }
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
                int autoIndex = deltas.size();
                for (JsonNode node : toolCallsNode) {
                    int index = node.has("index") ? node.path("index").asInt(autoIndex) : autoIndex;
                    autoIndex = Math.max(autoIndex + 1, index + 1);
                    DeltaToolCall acc = deltas.computeIfAbsent(index, k -> new DeltaToolCall());
                    String idFrag = node.path("id").asText("");
                    if (!idFrag.isEmpty()) acc.id.append(idFrag);
                    JsonNode fn = node.path("function");
                    String nameFrag = fn.path("name").asText("");
                    if (!nameFrag.isEmpty()) acc.name.append(nameFrag);
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

        List<ModelToolCall> toolCalls = buildDeltaToolCalls(deltas);

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

    private List<ModelToolCall> buildDeltaToolCalls(java.util.Map<Integer, DeltaToolCall> deltas) {
        List<ModelToolCall> calls = new ArrayList<>();
        for (java.util.Map.Entry<Integer, DeltaToolCall> entry : deltas.entrySet()) {
            DeltaToolCall acc = entry.getValue();
            String id = acc.id.toString();
            String name = acc.name.toString();
            String raw = acc.arguments.length() == 0 ? "{}" : acc.arguments.toString();
            if (name.isBlank()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "工具调用缺少 function.name");
            }
            JsonNode args;
            try {
                args = mapper.readTree(raw);
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
        body.put("max_tokens", config.maxOutputTokens());
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
            String raw = node.path("function").path("arguments").asText("{}");
            JsonNode args;
            try {
                args = mapper.readTree(raw);
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
        body.put("max_tokens", config.maxOutputTokens());
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
        if (metadata == null) return headers(apiKey);
        return Map.of("Authorization", "Bearer " + apiKey,
                "User-Agent", userAgent,
                OpenCodeZenTransport.SESSION_HEADER, metadata.correlationSessionId());
    }

    public ChatCompletionResult completeWithSession(ModelConfiguration config, String apiKey,
            com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand command, AiRequestMetadata metadata, String userAgent) {
        long started = System.nanoTime();
        JsonNode response = http.post(endpoint(config), headersWithSession(apiKey, metadata, userAgent), request(config, command, false));
        JsonNode choice = response.path("choices").path(0);
        if ("length".equals(choice.path("finish_reason").asText())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        }
        JsonNode usage = response.path("usage");
        return result(config, choice.path("message").path("content").asText(null),
                response.path("model").asText(config.modelName()),
                integer(usage.path("prompt_tokens")), integer(usage.path("completion_tokens")), started);
    }

    public ModelTurnResult turnWithSession(ModelConfiguration config, String apiKey, ModelTurnCommand command,
            AiRequestMetadata metadata, String userAgent) {
        long started = System.nanoTime();
        try {
            JsonNode response = http.post(endpoint(config), headersWithSession(apiKey, metadata, userAgent), turnRequest(config, command));
            return parseTurnResponse(config, response, started);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR
                    && config.capabilities().contains(ModelCapability.STREAMING)) {
                return turnStreamingSyncWithSession(config, apiKey, command, metadata, userAgent);
            }
            throw e;
        }
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
        http.stream(endpoint(config), headersWithSession(apiKey, metadata, userAgent), turnRequest(config, command, true, true), (event, data) -> {
            if (data == null) return;
            if (data.hasNonNull("model")) model.set(data.path("model").asText());
            JsonNode usageNode = data.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                usage.set(new ModelUsage(integer(usageNode.path("prompt_tokens")), integer(usageNode.path("completion_tokens"))));
            }
            JsonNode choice = data.path("choices").path(0);
            if (choice.isMissingNode()) return;
            String token = choice.path("delta").path("content").asText("");
            if (!token.isEmpty()) content.append(token);
            JsonNode toolCallsNode = choice.path("delta").path("tool_calls");
            if (toolCallsNode.isArray()) {
                int autoIndex = deltas.size();
                for (JsonNode node : toolCallsNode) {
                    int index = node.has("index") ? node.path("index").asInt(autoIndex) : autoIndex;
                    autoIndex = Math.max(autoIndex + 1, index + 1);
                    DeltaToolCall acc = deltas.computeIfAbsent(index, k -> new DeltaToolCall());
                    String idFrag = node.path("id").asText("");
                    if (!idFrag.isEmpty()) acc.id.append(idFrag);
                    String nameFrag = node.path("function").path("name").asText("");
                    if (!nameFrag.isEmpty()) acc.name.append(nameFrag);
                    String argsFrag = node.path("function").path("arguments").asText("");
                    if (!argsFrag.isEmpty()) acc.arguments.append(argsFrag);
                }
            }
            String finish = choice.path("finish_reason").asText("");
            if (!finish.isEmpty()) finishReason.set(mapFinishReason(finish));
        });
        List<ModelToolCall> toolCalls = buildDeltaToolCalls(deltas);
        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE, "model returned neither text nor tool calls");
        }
        if (finishReason.get() == ModelFinishReason.LENGTH) throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        return new ModelTurnResult(content.isEmpty() ? null : content.toString(), toolCalls, finishReason.get(),
                usage.get(), providerType().name(), model.get(), latencyMs);
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
        if ((content == null || content.isBlank()) && toolCalls.isEmpty()) throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        if (fr == ModelFinishReason.LENGTH) throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return new ModelTurnResult(content, toolCalls, fr, usage, providerType().name(), response.path("model").asText(config.modelName()), latencyMs);
    }
}
