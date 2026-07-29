package com.shitulelv.aicollab.agent.application.view;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentMessageView(
        UUID id,
        UUID sessionId,
        UUID runId,
        String role,
        String content,
        JsonNode citations,
        JsonNode inferences,
        OffsetDateTime createdAt) {
}
