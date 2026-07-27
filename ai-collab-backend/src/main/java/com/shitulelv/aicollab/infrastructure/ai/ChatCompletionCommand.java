package com.shitulelv.aicollab.infrastructure.ai;

public record ChatCompletionCommand(String systemPrompt, String userPrompt, OutputFormat outputFormat) {
    public ChatCompletionCommand(String systemPrompt, String userPrompt) {
        this(systemPrompt, userPrompt, OutputFormat.TEXT);
    }

    public enum OutputFormat {
        TEXT,
        JSON_OBJECT
    }
}
