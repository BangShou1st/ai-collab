package com.shitulelv.aicollab.agent.application.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentStepView(
        UUID id,
        int sequence,
        AgentStepType type,
        String toolName,
        JsonNode input,
        JsonNode output,
        String reason,
        Integer promptTokens,
        Integer completionTokens,
        boolean tokenUsageEstimated,
        Integer latencyMs,
        String errorCode,
        OffsetDateTime createdAt) {
}
