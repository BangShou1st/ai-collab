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
        JsonNode response = http.post(endpoint(config), headers(apiKey), request(config, command, false));
        JsonNode choice = response.path("choices").path(0);
        if ("length".equals(choice.path("finish_reason").asText())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        }
        JsonNode usage = response.path("usage");
        return result(config, choice.path("message").path("content").asText(null),
                response.path("model").asText(config.modelName()),
                integer(usage.path("prompt_tokens")), integer(usage.path("completion_tokens")), started);
    }

    @Override
    public void completeStream(
            ModelConfiguration config, String apiKey, ChatCompletionCommand command,
            Consumer<String> onToken, Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError) {
        safeStream(() -> {
            long started = System.nanoTime();
            StringBuilder content = new StringBuilder();
            AtomicReference<String> model = new AtomicReference<>(config.modelName());
            AtomicReference<Integer> input = new AtomicReference<>();
            AtomicReference<Integer> output = new AtomicReference<>();
            AtomicBoolean terminal = new AtomicBoolean(false);
            http.stream(endpoint(config), headers(apiKey), request(config, command, true), (event, data) -> {
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
    }

    // ========== 请求构建 ==========

    private ObjectNode turnRequest(ModelConfiguration config, ModelTurnCommand command) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.modelName());
        body.put("temperature", config.temperature());
        body.put("max_tokens", config.maxOutputTokens());

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
}
