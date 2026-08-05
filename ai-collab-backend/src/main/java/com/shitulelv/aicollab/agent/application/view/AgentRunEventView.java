package com.shitulelv.aicollab.agent.application.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentRunEventView(
        UUID id,
        UUID projectId,
        UUID runId,
        long sequence,
        AgentEventType type,
        JsonNode payload,
        OffsetDateTime createdAt) {
}
