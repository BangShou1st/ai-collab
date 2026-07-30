package com.shitulelv.aicollab.agent.api.dto;

import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.common.api.JsonApiValue;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentStepResponse(
        UUID id,
        int sequence,
        AgentStepType type,
        String toolName,
        Object input,
        Object output,
        String reason,
        Integer promptTokens,
        Integer completionTokens,
        boolean tokenUsageEstimated,
        Integer latencyMs,
        String errorCode,
        OffsetDateTime createdAt) {

    public static AgentStepResponse from(AgentStepView view) {
        return new AgentStepResponse(
                view.id(), view.sequence(), view.type(), view.toolName(),
                JsonApiValue.from(view.input()), JsonApiValue.from(view.output()), view.reason(),
                view.promptTokens(), view.completionTokens(), view.tokenUsageEstimated(),
                view.latencyMs(), view.errorCode(), view.createdAt());
    }
}
