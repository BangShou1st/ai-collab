package com.shitulelv.aicollab.agent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record AgentPageContextRequest(
        @Size(max = 80) String route,
        UUID selectedTaskId,
        UUID selectedMilestoneId,
        UUID selectedDocumentId,
        UUID selectedPlanId,
        Map<@Size(max = 80) String, JsonNode> filters) {
    public AgentPageContextRequest {
        if (filters == null) filters = Map.of();
        else filters = Map.copyOf(filters);
        if (filters.size() > 20) throw new IllegalArgumentException("filters 过多");
    }

    public AgentPageContextRequest(
            String route, UUID selectedTaskId, UUID selectedMilestoneId,
            UUID selectedDocumentId, Map<String, JsonNode> filters) {
        this(route, selectedTaskId, selectedMilestoneId, selectedDocumentId, null, filters);
    }
}
