package com.shitulelv.aicollab.agent.api.dto;

import com.shitulelv.aicollab.agent.application.view.AgentRunDetailView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;

import java.util.List;

public record AgentRunDetailResponse(AgentRunView run, List<AgentStepResponse> steps) {
    public static AgentRunDetailResponse from(AgentRunDetailView view) {
        return new AgentRunDetailResponse(
                view.run(), view.steps().stream().map(AgentStepResponse::from).toList());
    }
}
