package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;

import java.util.function.Consumer;

abstract class AbstractModelProviderAdapter implements ModelProviderAdapter {
    protected final ObjectMapper mapper;
    protected final JsonHttpModelClient http;

    protected AbstractModelProviderAdapter(ObjectMapper mapper, JsonHttpModelClient http) {
        this.mapper = mapper;
        this.http = http;
    }

    protected static String endpoint(ModelConfiguration config) {
        String base = config.baseUrl().strip();
        String path = config.apiPath().strip();
        return base.endsWith("/") && path.startsWith("/") ? base + path.substring(1)
                : !base.endsWith("/") && !path.startsWith("/") ? base + "/" + path : base + path;
    }

    protected ChatCompletionResult result(
            ModelConfiguration config, String content, String responseModel,
            Integer inputTokens, Integer outputTokens, long started) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
        return new ChatCompletionResult(
                content.strip(), providerType().name(), responseModel == null ? config.modelName() : responseModel,
                inputTokens, outputTokens, Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
    }

    protected void addCommonTools(ArrayNode target, ChatCompletionCommand command, ToolShape shape) {
        for (ModelToolDefinition tool : command.tools()) {
            ObjectNode item = target.addObject();
            shape.write(item, tool);
        }
    }

    protected static Integer integer(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    @FunctionalInterface
    protected interface ToolShape {
        void write(ObjectNode target, ModelToolDefinition tool);
    }

    protected void safeStream(StreamOperation operation, Consumer<Exception> onError) {
        try {
            operation.run();
        } catch (Exception exception) {
            onError.accept(exception instanceof BusinessException
                    ? exception : new BusinessException(ErrorCode.AI_PROVIDER_ERROR));
        }
    }

    @FunctionalInterface
    protected interface StreamOperation {
        void run();
    }
}
