package com.shitulelv.aicollab.agent.api.dto;

import com.shitulelv.aicollab.agent.application.view.AgentRunDetailView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;

import java.util.List;

public record AgentRunDetailResponse(
        AgentRunView run, com.fasterxml.jackson.databind.JsonNode plan,
        List<AgentStepResponse> steps, long lastEventSequence, java.util.UUID pendingApprovalId) {
    public static AgentRunDetailResponse from(AgentRunDetailView view) {
        return new AgentRunDetailResponse(
                view.run(), view.plan(), view.steps().stream().map(AgentStepResponse::from).toList(),
                view.lastEventSequence(), view.pendingApprovalId());
    }
}
