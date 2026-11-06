package com.shitulelv.aicollab.retrieval.query;

import java.time.OffsetDateTime;

/**
 * Clean model representing a single message in multi-turn retrieval conversation history.
 */
public record ConversationMessage(
        String role,
        String content,
        OffsetDateTime createdAt,
        boolean insufficientEvidence) {

    public ConversationMessage(String role, String content) {
        this(role, content, null, false);
    }

    public boolean isUser() {
        return "USER".equalsIgnoreCase(role);
    }

    public boolean isAssistant() {
        return "ASSISTANT".equalsIgnoreCase(role);
    }
}
