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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class AnthropicModelAdapter extends AbstractModelProviderAdapter
        implements ModelTurnProviderAdapter {
    public AnthropicModelAdapter(ObjectMapper mapper, JsonHttpModelClient http) {
        super(mapper, http);
    }

    @Override
    public ModelProviderType providerType() {
        return ModelProviderType.ANTHROPIC;
    }

    // ========== 旧接口保持不变 ==========

    @Override
    public ChatCompletionResult complete(
            ModelConfiguration config, String apiKey, ChatCompletionCommand command) {
        long started = System.nanoTime();
        JsonNode response = http.post(endpoint(config), headers(apiKey), request(config, command, false));
        if ("max_tokens".equals(response.path("stop_reason").asText())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        }
        StringBuilder content = new StringBuilder();
        response.path("content").forEach(block -> {
            if ("text".equals(block.path("type").asText())) content.append(block.path("text").asText());
        });
        JsonNode usage = response.path("usage");
        return result(config, content.toString(), response.path("model").asText(config.modelName()),
                integer(usage.path("input_tokens")), integer(usage.path("output_tokens")), started);
    }

    @Override
    public void completeStream(
            ModelConfiguration config, String apiKey, ChatCompletionCommand command,
            Consumer<String> onToken, Consumer<ChatCompletionResult> onDone,
            Consumer<Exception> onError) {
        safeStream(() -> {
            long started = System.nanoTime();
            StringBuilder content = new StringBuilder();
            AtomicReference<Integer> input = new AtomicReference<>();
            AtomicReference<Integer> output = new AtomicReference<>();
            http.stream(endpoint(config), headers(apiKey), request(config, command, true), (event, data) -> {
                if (data == null) return;
                if ("error".equals(event) || "error".equals(data.path("type").asText())) {
                    throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
                }
                if ("message_start".equals(data.path("type").asText())) {
                    input.set(integer(data.path("message").path("usage").path("input_tokens")));
                }
                if ("content_block_delta".equals(data.path("type").asText())
                        && "text_delta".equals(data.path("delta").path("type").asText())) {
                    String token = data.path("delta").path("text").asText("");
                    content.append(token);
                    onToken.accept(token);
                }
                if ("message_delta".equals(data.path("type").asText())) {
                    output.set(integer(data.path("usage").path("output_tokens")));
                    if ("max_tokens".equals(data.path("delta").path("stop_reason").asText())) {
                        throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
                    }
                }
            });
            onDone.accept(result(config, content.toString(), config.modelName(),
                    input.get(), output.get(), started));
        }, onError);
    }

    // ========== 新接口：原生 Tool Calling ==========

    @Override
    public ModelTurnResult turn(ModelConfiguration config, String apiKey, ModelTurnCommand command) {
        long started = System.nanoTime();
        JsonNode response = http.post(endpoint(config), headers(apiKey), turnRequest(config, command));

        // 解析 stop_reason
        String stopReason = response.path("stop_reason").asText("");
        ModelFinishReason finishReason = mapFinishReason(stopReason);
        if (finishReason == ModelFinishReason.LENGTH) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        }

        // 遍历 content blocks，同时收集文本和 tool_use
        StringBuilder textContent = new StringBuilder();
        List<ModelToolCall> toolCalls = new ArrayList<>();
        for (JsonNode block : response.path("content")) {
            String type = block.path("type").asText();
            switch (type) {
                case "text" -> textContent.append(block.path("text").asText());
                case "tool_use" -> {
                    String id = block.path("id").asText();
                    String name = block.path("name").asText();
                    JsonNode input = block.path("input");
                    if (input == null || input.isNull() || input.isMissingNode()) {
                        input = mapper.createObjectNode();
                    }
                    toolCalls.add(new ModelToolCall(id, name, input));
                }
                default -> { /* 忽略未知 block，但不能当工具 */ }
            }
        }

        String content = textContent.toString();
        String responseModel = response.path("model").asText(config.modelName());

        // 既没有文本也没有 tool_use 是协议错误
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "模型既没有返回文本也没有返回工具调用");
        }

        // 解析 usage
        JsonNode usageNode = response.path("usage");
        ModelUsage usage = null;
        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
            usage = new ModelUsage(
                    integer(usageNode.path("input_tokens")),
                    integer(usageNode.path("output_tokens")));
        }

        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return new ModelTurnResult(
                content, toolCalls, finishReason, usage,
                providerType().name(), responseModel, latencyMs);
    }

    // ========== 请求构建（新接口） ==========

    private ObjectNode turnRequest(ModelConfiguration config, ModelTurnCommand command) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.modelName());
        body.put("max_tokens", config.maxOutputTokens());

        // 提取 system 消息，放入顶层 system 字段
        StringBuilder systemPrompt = new StringBuilder();
        ArrayNode messages = body.putArray("messages");

        // 收集连续的 ToolResult，合并为单个 user message
        List<ModelMessage.ToolResult> pendingToolResults = new ArrayList<>();

        for (ModelMessage message : command.messages()) {
            switch (message) {
                case ModelMessage.System m -> {
                    if (!systemPrompt.isEmpty()) systemPrompt.append("\n");
                    systemPrompt.append(m.content());
                }
                case ModelMessage.User m -> {
                    flushToolResults(messages, pendingToolResults);
                    messages.addObject().put("role", "user").put("content", m.content());
                }
                case ModelMessage.Assistant m -> {
                    flushToolResults(messages, pendingToolResults);
                    ObjectNode assistantMsg = messages.addObject().put("role", "assistant");
                    ArrayNode contentBlocks = assistantMsg.putArray("content");
                    if (!m.content().isBlank()) {
                        ObjectNode textBlock = contentBlocks.addObject();
                        textBlock.put("type", "text").put("text", m.content());
                    }
                    for (ModelToolCall call : m.toolCalls()) {
                        ObjectNode toolBlock = contentBlocks.addObject();
                        toolBlock.put("type", "tool_use");
                        toolBlock.put("id", call.id());
                        toolBlock.put("name", call.name());
                        toolBlock.set("input", call.arguments());
                    }
                }
                case ModelMessage.ToolResult m -> pendingToolResults.add(m);
            }
        }
        flushToolResults(messages, pendingToolResults);

        if (!systemPrompt.isEmpty()) {
            body.put("system", systemPrompt.toString());
        }

        // 添加工具定义
        if (!command.tools().isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ModelToolDefinition tool : command.tools()) {
                ObjectNode toolObj = toolsArray.addObject();
                toolObj.put("name", tool.name());
                toolObj.put("description", tool.description());
                toolObj.set("input_schema", tool.inputSchema());
            }
        }

        return body;
    }

    // ========== 旧请求构建（保持不变）==========

    private ObjectNode request(
            ModelConfiguration config, ChatCompletionCommand command, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.modelName());
        body.put("max_tokens", config.maxOutputTokens());
        body.put("stream", stream);
        body.put("system", command.systemPrompt());
        body.putArray("messages").addObject()
                .put("role", "user").put("content", command.userPrompt());
        if (!command.tools().isEmpty()) {
            addCommonTools(body.putArray("tools"), command, (target, tool) -> {
                target.put("name", tool.name());
                target.put("description", tool.description());
                target.set("input_schema", tool.inputSchema());
            });
        }
        if (command.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT
                && command.outputSchema() != null) {
            body.putObject("output_config").putObject("format")
                    .put("type", "json_schema")
                    .set("schema", command.outputSchema());
        }
        return body;
    }

    private static ModelFinishReason mapFinishReason(String reason) {
        if (reason == null) return ModelFinishReason.UNKNOWN;
        return switch (reason) {
            case "end_turn", "stop" -> ModelFinishReason.STOP;
            case "tool_use" -> ModelFinishReason.TOOL_CALLS;
            case "max_tokens" -> ModelFinishReason.LENGTH;
            default -> ModelFinishReason.UNKNOWN;
        };
    }

    private static Map<String, String> headers(String apiKey) {
        return Map.of(
                "x-api-key", apiKey,
                "anthropic-version", "2023-06-01");
    }

    /**
     * 将收集的连续 ToolResult 合并为单个 user message 的 tool_result content blocks。
     * Anthropic 要求同一轮所有 tool_result 在同一个 user message 中。
     */
    private static void flushToolResults(ArrayNode messages,
                                         List<ModelMessage.ToolResult> pending) {
        if (pending.isEmpty()) return;
        ObjectNode toolResultMsg = messages.addObject().put("role", "user");
        ArrayNode contentBlocks = toolResultMsg.putArray("content");
        for (ModelMessage.ToolResult m : pending) {
            ObjectNode toolResultBlock = contentBlocks.addObject();
            toolResultBlock.put("type", "tool_result");
            toolResultBlock.put("tool_use_id", m.toolCallId());
            toolResultBlock.put("is_error", m.error());
            String resultContent = m.result().isTextual()
                    ? m.result().asText()
                    : m.result().toString();
            toolResultBlock.put("content", resultContent);
        }
        pending.clear();
    }
}
