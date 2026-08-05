package com.shitulelv.aicollab.agent.application.view;

import java.util.List;

public record AgentRunDetailView(
        AgentRunView run, com.fasterxml.jackson.databind.JsonNode plan,
        List<AgentStepView> steps, long lastEventSequence, java.util.UUID pendingApprovalId) {
    public AgentRunDetailView(AgentRunView run, List<AgentStepView> steps) {
        this(run, null, steps, 0, null);
    }
}
