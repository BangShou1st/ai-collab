package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
public class AnthropicModelAdapter extends AbstractModelProviderAdapter {
    public AnthropicModelAdapter(ObjectMapper mapper, JsonHttpModelClient http) {
        super(mapper, http);
    }

    @Override
    public ModelProviderType providerType() {
        return ModelProviderType.ANTHROPIC;
    }

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

    private static Map<String, String> headers(String apiKey) {
        return Map.of(
                "x-api-key", apiKey,
                "anthropic-version", "2023-06-01");
    }
}
