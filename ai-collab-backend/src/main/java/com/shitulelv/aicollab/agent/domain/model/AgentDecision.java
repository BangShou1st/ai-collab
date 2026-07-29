package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

public sealed interface AgentDecision
        permits AgentDecision.CallTool, AgentDecision.Delegate, AgentDecision.FinalAnswer {

    record CallTool(String tool, JsonNode arguments, String reason) implements AgentDecision {
    }

    record Delegate(String role, String objective) implements AgentDecision {
    }

    record FinalAnswer(
            String answer,
            JsonNode citations,
            JsonNode inferences) implements AgentDecision {
    }
}
