package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentInference;

import java.util.List;

public record AgentToolResult(
        JsonNode data,
        List<AgentCitation> citations,
        List<AgentInference> inferences) {

    public AgentToolResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        inferences = inferences == null ? List.of() : List.copyOf(inferences);
    }
}
