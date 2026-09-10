package com.shitulelv.aicollab.agent.api.dto;

import com.shitulelv.aicollab.agent.application.view.AgentRunEventView;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentRunEventResponse(UUID id, UUID projectId, UUID runId, long sequence,
        String type, com.fasterxml.jackson.databind.JsonNode payload, OffsetDateTime createdAt) {
    public static AgentRunEventResponse from(AgentRunEventView view) {
        return new AgentRunEventResponse(view.id(), view.projectId(), view.runId(), view.sequence(),
                view.type().name(), view.payload(), view.createdAt());
    }
}
