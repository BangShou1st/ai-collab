package com.shitulelv.aicollab.agent.api.dto;

import com.shitulelv.aicollab.agent.application.view.AgentMessageView;
import com.shitulelv.aicollab.common.api.JsonApiValue;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentMessageResponse(
        UUID id,
        UUID sessionId,
        UUID runId,
        String role,
        String content,
        Object citations,
        Object inferences,
        OffsetDateTime createdAt) {

    public static AgentMessageResponse from(AgentMessageView view) {
        return new AgentMessageResponse(
                view.id(), view.sessionId(), view.runId(), view.role(), view.content(),
                JsonApiValue.from(view.citations()), JsonApiValue.from(view.inferences()),
                view.createdAt());
    }
}
