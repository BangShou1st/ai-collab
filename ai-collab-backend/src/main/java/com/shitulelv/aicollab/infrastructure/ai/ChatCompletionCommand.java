package com.shitulelv.aicollab.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelToolDefinition;

import java.util.List;
import java.util.UUID;

public record ChatCompletionCommand(
        UUID projectId,
        String systemPrompt,
        String userPrompt,
        OutputFormat outputFormat,
        ModelPurpose purpose,
        JsonNode outputSchema,
        List<ModelToolDefinition> tools) {
    public ChatCompletionCommand(UUID projectId, String systemPrompt, String userPrompt) {
        this(projectId, systemPrompt, userPrompt, OutputFormat.TEXT);
    }

    public ChatCompletionCommand(String systemPrompt, String userPrompt) {
        this(null, systemPrompt, userPrompt, OutputFormat.TEXT);
    }

    public ChatCompletionCommand(String systemPrompt, String userPrompt, OutputFormat outputFormat) {
        this(null, systemPrompt, userPrompt, outputFormat);
    }

    public ChatCompletionCommand(UUID projectId, String systemPrompt, String userPrompt, OutputFormat outputFormat) {
        this(projectId, systemPrompt, userPrompt, outputFormat, ModelPurpose.KNOWLEDGE_CHAT, null, List.of());
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
