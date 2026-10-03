package com.shitulelv.aicollab.agent.application.view;

import java.util.List;

public record AgentRunDetailView(
        AgentRunView run, com.fasterxml.jackson.databind.JsonNode plan,
        List<AgentStepView> steps, long lastEventSequence, java.util.UUID pendingApprovalId,
        com.fasterxml.jackson.databind.JsonNode modelConfiguration, java.util.Map<String,Integer> recoveryCounters) {
    public AgentRunDetailView(AgentRunView run, com.fasterxml.jackson.databind.JsonNode plan,
            List<AgentStepView> steps,long lastEventSequence,java.util.UUID pendingApprovalId) {
        this(run,plan,steps,lastEventSequence,pendingApprovalId,null,java.util.Map.of());
    }
    public AgentRunDetailView(AgentRunView run, List<AgentStepView> steps) {
        this(run, null, steps, 0, null);
    }
}
