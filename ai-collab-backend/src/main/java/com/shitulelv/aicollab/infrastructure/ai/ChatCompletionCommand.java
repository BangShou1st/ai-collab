package com.shitulelv.aicollab.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelToolDefinition;

import java.util.List;

public record ChatCompletionCommand(
        String systemPrompt,
        String userPrompt,
        OutputFormat outputFormat,
        ModelPurpose purpose,
        JsonNode outputSchema,
        List<ModelToolDefinition> tools) {
    public ChatCompletionCommand(String systemPrompt, String userPrompt) {
        this(systemPrompt, userPrompt, OutputFormat.TEXT);
    }

    public ChatCompletionCommand(String systemPrompt, String userPrompt, OutputFormat outputFormat) {
        this(systemPrompt, userPrompt, outputFormat, ModelPurpose.KNOWLEDGE_CHAT, null, List.of());
    }

    public ChatCompletionCommand {
        purpose = purpose == null ? ModelPurpose.KNOWLEDGE_CHAT : purpose;
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public enum OutputFormat {
        TEXT,
        JSON_OBJECT
    }
}
