package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class GeminiModelAdapter extends AbstractModelProviderAdapter {
    public GeminiModelAdapter(ObjectMapper mapper, JsonHttpModelClient http) {
        super(mapper, http);
    }

    @Override
    public ModelProviderType providerType() {
        return ModelProviderType.GEMINI;
    }

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
