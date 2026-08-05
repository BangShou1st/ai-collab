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
public class GeminiModelAdapter extends AbstractModelProviderAdapter
        implements ModelTurnProviderAdapter {
    public GeminiModelAdapter(ObjectMapper mapper, JsonHttpModelClient http) {
        super(mapper, http);
    }

    @Override
    public ModelProviderType providerType() {
        return ModelProviderType.GEMINI;
    }

    // ========== 旧接口保持不变 ==========

    @Override
    public ChatCompletionResult complete(
            ModelConfiguration config, String apiKey, ChatCompletionCommand command) {
        long started = System.nanoTime();
        JsonNode response = http.post(modelEndpoint(config, false), headers(apiKey),
                request(config, command));
        return parse(config, response, started);
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
            AtomicReference<String> model = new AtomicReference<>(config.modelName());
            http.stream(modelEndpoint(config, true), headers(apiKey), request(config, command), (event, data) -> {
                if (data == null) return;
                String finish = data.path("candidates").path(0).path("finishReason").asText("");
                if ("MAX_TOKENS".equals(finish)) {
                    throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
                }
                String token = text(data);
                if (!token.isEmpty()) {
                    content.append(token);
                    onToken.accept(token);
                }
                JsonNode usage = data.path("usageMetadata");
                input.set(integer(usage.path("promptTokenCount")));
                output.set(integer(usage.path("candidatesTokenCount")));
                if (data.hasNonNull("modelVersion")) model.set(data.path("modelVersion").asText());
            });
            onDone.accept(result(config, content.toString(), model.get(), input.get(), output.get(), started));
        }, onError);
    }

    // ========== 新接口：原生 Tool Calling ==========

    @Override
    public ModelTurnResult turn(ModelConfiguration config, String apiKey, ModelTurnCommand command) {
        long started = System.nanoTime();
        JsonNode response = http.post(modelEndpoint(config, false), headers(apiKey),
                turnRequest(config, command));

        // 解析 finishReason
        String finishReasonText = response.path("candidates").path(0).path("finishReason").asText("");
        ModelFinishReason finishReason = mapFinishReason(finishReasonText);
        if (finishReason == ModelFinishReason.LENGTH) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        }

        // 统计消息历史中已有的 functionCall 数量，确保 ID 跨轮次唯一
        int existingFunctionCalls = 0;
        for (ModelMessage msg : command.messages()) {
            if (msg instanceof ModelMessage.Assistant a) {
                existingFunctionCalls += a.toolCalls().size();
            }
        }

        // 遍历 parts，同时收集文本和 functionCall
        StringBuilder textContent = new StringBuilder();
        List<ModelToolCall> toolCalls = new ArrayList<>();
        int partIndex = 0;
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (part.hasNonNull("text")) {
                textContent.append(part.path("text").asText());
            }
            if (part.hasNonNull("functionCall")) {
                JsonNode fc = part.path("functionCall");
                String name = fc.path("name").asText();
                JsonNode args = fc.path("args");
                if (args == null || args.isNull() || args.isMissingNode()) {
                    args = mapper.createObjectNode();
                }
                // Gemini 没有稳定的 tool call ID，生成内部稳定 ID
                // 偏移已有 functionCall 数量，确保跨轮次唯一
                String id = "gemini-call-" + (existingFunctionCalls + partIndex);
                toolCalls.add(new ModelToolCall(id, name, args));
            }
            partIndex++;
        }

        String content = textContent.toString();
        String responseModel = response.path("modelVersion").asText(config.modelName());

        // 既没有文本也没有 functionCall 是协议错误
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "模型既没有返回文本也没有返回函数调用");
        }

        // 解析 usage
        JsonNode usageNode = response.path("usageMetadata");
        ModelUsage usage = null;
        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
            usage = new ModelUsage(
                    integer(usageNode.path("promptTokenCount")),
                    integer(usageNode.path("candidatesTokenCount")));
        }

        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return new ModelTurnResult(
                content, toolCalls, finishReason, usage,
                providerType().name(), responseModel, latencyMs);
    }

    // ========== 请求构建（新接口） ==========

    private ObjectNode turnRequest(ModelConfiguration config, ModelTurnCommand command) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("temperature", config.temperature());
        generation.put("maxOutputTokens", config.maxOutputTokens());

        // 提取 system 消息，放入 systemInstruction
        StringBuilder systemPrompt = new StringBuilder();
        ArrayNode contents = body.putArray("contents");

        for (ModelMessage message : command.messages()) {
            switch (message) {
                case ModelMessage.System m -> {
                    if (!systemPrompt.isEmpty()) systemPrompt.append("\n");
                    systemPrompt.append(m.content());
                }
                case ModelMessage.User m -> contents.addObject()
                        .put("role", "user").putArray("parts").addObject()
                        .put("text", m.content());
                case ModelMessage.Assistant m -> {
                    ObjectNode assistantContent = contents.addObject().put("role", "model");
                    ArrayNode parts = assistantContent.putArray("parts");
                    if (!m.content().isBlank()) {
                        parts.addObject().put("text", m.content());
                    }
                    for (ModelToolCall call : m.toolCalls()) {
                        ObjectNode fcPart = parts.addObject();
                        ObjectNode fc = fcPart.putObject("functionCall");
                        fc.put("name", call.name());
                        fc.set("args", call.arguments());
                    }
                }
                case ModelMessage.ToolResult m -> {
                    ObjectNode functionResponseContent = contents.addObject().put("role", "user");
                    ArrayNode parts = functionResponseContent.putArray("parts");
                    ObjectNode frPart = parts.addObject();
                    ObjectNode fr = frPart.putObject("functionResponse");
                    fr.put("name", m.toolName());
                    ObjectNode response = fr.putObject("response");
                    // 将结果放入 response
                    if (m.result().isObject()) {
                        response.setAll((ObjectNode) m.result());
                    } else {
                        response.put("result", m.result().toString());
                    }
                }
            }
        }

        if (!systemPrompt.isEmpty()) {
            body.putObject("systemInstruction").putArray("parts").addObject()
                    .put("text", systemPrompt.toString());
        }

        // 添加工具定义
        if (!command.tools().isEmpty()) {
            ObjectNode toolsWrapper = body.putArray("tools").addObject();
            ArrayNode declarations = toolsWrapper.putArray("functionDeclarations");
            for (ModelToolDefinition tool : command.tools()) {
                ObjectNode decl = declarations.addObject();
                decl.put("name", tool.name());
                decl.put("description", tool.description());
                decl.set("parameters", tool.inputSchema());
            }
        }

        return body;
    }

    // ========== 旧请求构建（保持不变）==========

    private ObjectNode request(ModelConfiguration config, ChatCompletionCommand command) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject()
                .put("text", command.systemPrompt());
        body.putArray("contents").addObject()
                .put("role", "user").putArray("parts").addObject()
                .put("text", command.userPrompt());
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("temperature", config.temperature());
        generation.put("maxOutputTokens", config.maxOutputTokens());
        if (command.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT) {
            generation.put("responseMimeType", "application/json");
            if (command.outputSchema() != null) {
                generation.set("responseJsonSchema", command.outputSchema());
            }
        }
        if (!command.tools().isEmpty()) {
            ObjectNode declarations = body.putArray("tools").addObject();
            addCommonTools(declarations.putArray("functionDeclarations"), command, (target, tool) -> {
                target.put("name", tool.name());
                target.put("description", tool.description());
                target.set("parameters", tool.inputSchema());
            });
        }
        return body;
    }

    private ChatCompletionResult parse(ModelConfiguration config, JsonNode response, long started) {
        String finish = response.path("candidates").path(0).path("finishReason").asText("");
        if ("MAX_TOKENS".equals(finish)) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        }
        JsonNode usage = response.path("usageMetadata");
        return result(config, text(response), response.path("modelVersion").asText(config.modelName()),
                integer(usage.path("promptTokenCount")), integer(usage.path("candidatesTokenCount")), started);
    }

    private static String text(JsonNode response) {
        StringBuilder content = new StringBuilder();
        response.path("candidates").path(0).path("content").path("parts").forEach(part -> {
            if (part.hasNonNull("text")) content.append(part.path("text").asText());
        });
        return content.toString();
    }

    private static ModelFinishReason mapFinishReason(String reason) {
        if (reason == null) return ModelFinishReason.UNKNOWN;
        return switch (reason) {
            case "STOP" -> ModelFinishReason.STOP;
            case "MAX_TOKENS" -> ModelFinishReason.LENGTH;
            case "SAFETY" -> ModelFinishReason.CONTENT_FILTER;
            default -> ModelFinishReason.UNKNOWN;
        };
    }

    private static Map<String, String> headers(String apiKey) {
        return Map.of("x-goog-api-key", apiKey);
    }

    private static String modelEndpoint(ModelConfiguration config, boolean stream) {
        String base = endpoint(config).replace("{model}", config.modelName());
        if (!stream) return base;
        if (base.contains(":generateContent")) {
            base = base.replace(":generateContent", ":streamGenerateContent");
        }
        return base + (base.contains("?") ? "&" : "?") + "alt=sse";
    }
}
