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
            AtomicReference<String> finishReason = new AtomicReference<>(null);
            java.util.concurrent.atomic.AtomicInteger chunkCount = new java.util.concurrent.atomic.AtomicInteger(0);
            http.stream(modelEndpoint(config, true), headers(apiKey), request(config, command), (event, data) -> {
                if (data == null) return;
                chunkCount.incrementAndGet();
                JsonNode candidate = data.path("candidates").path(0);
                String finish = candidate.path("finishReason").asText("");
                if (!finish.isBlank()) {
                    finishReason.set(finish);
                }
                if ("MAX_TOKENS".equals(finish)) {
                    throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
                }
                if (isSafetyFinishReason(finish)) {
                    throw new BusinessException(ErrorCode.AI_PROVIDER_REQUEST_REJECTED,
                            "Gemini 流因安全策略终止: " + finish);
                }
                String token = text(data);
                if (!token.isEmpty()) {
                    content.append(token);
                    onToken.accept(token);
                }
                JsonNode usage = data.path("usageMetadata");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    if (usage.hasNonNull("promptTokenCount")) {
                        input.set(integer(usage.path("promptTokenCount")));
                    }
                    if (usage.hasNonNull("candidatesTokenCount")) {
                        output.set(integer(usage.path("candidatesTokenCount")));
                    }
                }
                if (data.hasNonNull("modelVersion")) model.set(data.path("modelVersion").asText());
            }, false);

            if (chunkCount.get() == 0) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "PROVIDER_STREAM / EMPTY_STREAM", input.get(), output.get());
            }
            if (finishReason.get() == null) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "PROVIDER_STREAM / TRUNCATED_BEFORE_FINISH", input.get(), output.get());
            }
            if (!"STOP".equals(finishReason.get())) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "PROVIDER_STREAM / UNEXPECTED_FINISH_REASON / finish=" + finishReason.get(), input.get(), output.get());
            }
            if (content.toString().isBlank()) {
                throw new ProviderResponseFailure(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                        "PROVIDER_STREAM / EMPTY_CONTENT / finish=" + finishReason.get(), input.get(), output.get());
            }
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
        if (isSafetyFinishReason(finishReasonText)) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_REQUEST_REJECTED,
                    "Gemini 因安全策略终止: " + finishReasonText);
        }
        ModelFinishReason finishReason = mapFinishReason(finishReasonText);
        if (finishReason == ModelFinishReason.LENGTH) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
        }

        // 统计本次请求仍可见的历史调用，仅用于适配器阶段的临时 ID；运行级 ID 在持久化前由 Coordinator 重定界。
        int existingFunctionCalls = 0;
        for (ModelMessage msg : command.messages()) {
            if (msg instanceof ModelMessage.Assistant a) {
                existingFunctionCalls += a.toolCalls().size();
            }
        }

        // 遍历 parts，同时收集文本和 functionCall
        StringBuilder textContent = new StringBuilder();
        List<ModelToolCall> toolCalls = new ArrayList<>();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (part.hasNonNull("text") && !part.path("thought").asBoolean(false)) {
                textContent.append(part.path("text").asText());
            }
            if (part.hasNonNull("functionCall")) {
                JsonNode fc = part.path("functionCall");
                String name = fc.path("name").asText();
                JsonNode args = fc.path("args");
                if (args == null || args.isNull() || args.isMissingNode()) {
                    args = mapper.createObjectNode();
                }
                String thoughtSignature = null;
                if (part.hasNonNull("thoughtSignature")) {
                    thoughtSignature = part.path("thoughtSignature").asText();
                } else if (part.hasNonNull("thought_signature")) {
                    thoughtSignature = part.path("thought_signature").asText();
                } else if (fc.hasNonNull("thoughtSignature")) {
                    thoughtSignature = fc.path("thoughtSignature").asText();
                } else if (fc.hasNonNull("thought_signature")) {
                    thoughtSignature = fc.path("thought_signature").asText();
                }
                // Gemini 没有原生 tool call ID。这里生成基于可见历史的临时值；
                // 上下文裁剪可能隐藏旧调用，运行级唯一值由 AgentRuntimeCoordinator 按持久化调用计数赋予。
                String id = "gemini-call-" + (existingFunctionCalls + toolCalls.size());
                toolCalls.add(new ModelToolCall(id, name, args, thoughtSignature));
            }
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
        generation.put("maxOutputTokens", AiRequestOutputCap.effective(config.maxOutputTokens()));

        // 提取 system 消息，放入 systemInstruction
        StringBuilder systemPrompt = new StringBuilder();
        ArrayNode contents = body.putArray("contents");

        ObjectNode lastContent = null;
        for (ModelMessage message : command.messages()) {
            switch (message) {
                case ModelMessage.System m -> {
                    if (!systemPrompt.isEmpty()) systemPrompt.append("\n");
                    systemPrompt.append(m.content());
                }
                case ModelMessage.User m -> {
                    if (m.content() == null || m.content().isBlank()) break;
                    if (lastContent != null && "user".equals(lastContent.path("role").asText())) {
                        ((ArrayNode) lastContent.path("parts")).addObject().put("text", m.content());
                    } else {
                        lastContent = contents.addObject().put("role", "user");
                        lastContent.putArray("parts").addObject().put("text", m.content());
                    }
                }
                case ModelMessage.Assistant m -> {
                    boolean hasText = !m.content().isBlank();
                    boolean hasTools = !m.toolCalls().isEmpty();
                    if (!hasText && !hasTools) break; // 避免生成 parts: [] 空 content
                    ArrayNode parts;
                    if (lastContent != null && "model".equals(lastContent.path("role").asText())) {
                        parts = (ArrayNode) lastContent.path("parts");
                    } else {
                        lastContent = contents.addObject().put("role", "model");
                        parts = lastContent.putArray("parts");
                    }
                    if (hasText) {
                        parts.addObject().put("text", m.content());
                    }
                    for (ModelToolCall call : m.toolCalls()) {
                        ObjectNode fcPart = parts.addObject();
                        ObjectNode fc = fcPart.putObject("functionCall");
                        fc.put("name", call.name());
                        fc.set("args", call.arguments());
                        if (call.thoughtSignature() != null && !call.thoughtSignature().isBlank()) {
                            fcPart.put("thoughtSignature", call.thoughtSignature());
                        }
                    }
                }
                case ModelMessage.ToolResult m -> {
                    ArrayNode parts;
                    if (lastContent != null && "user".equals(lastContent.path("role").asText())) {
                        parts = (ArrayNode) lastContent.path("parts");
                    } else {
                        lastContent = contents.addObject().put("role", "user");
                        parts = lastContent.putArray("parts");
                    }
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
        generation.put("maxOutputTokens", AiRequestOutputCap.effective(config.maxOutputTokens()));
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
        if (isSafetyFinishReason(finish)) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_REQUEST_REJECTED,
                    "Gemini 因安全策略终止: " + finish);
        }
        JsonNode usage = response.path("usageMetadata");
        return result(config, text(response), response.path("modelVersion").asText(config.modelName()),
                integer(usage.path("promptTokenCount")), integer(usage.path("candidatesTokenCount")), started);
    }

    private static String text(JsonNode response) {
        StringBuilder content = new StringBuilder();
        response.path("candidates").path(0).path("content").path("parts").forEach(part -> {
            if (part.hasNonNull("text") && !part.path("thought").asBoolean(false)) {
                content.append(part.path("text").asText());
            }
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

    private static boolean isSafetyFinishReason(String reason) {
        if (reason == null || reason.isBlank()) return false;
        return "SAFETY".equals(reason)
                || "RECITATION".equals(reason)
                || "BLOCKLIST".equals(reason)
                || "PROHIBITED_CONTENT".equals(reason)
                || "SPII".equals(reason);
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
