package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentInference;

import java.util.List;

public record AgentToolResult(
        JsonNode data,
        List<AgentCitation> citations,
        List<AgentInference> inferences,
        Status status,
        ToolError error) {

    public enum Status { SUCCEEDED, FAILED, REJECTED, CANCELED, SKIPPED, UNKNOWN }
    public record ToolError(String code, String message, boolean retryable) {}

    public AgentToolResult(JsonNode data, List<AgentCitation> citations, List<AgentInference> inferences) {
        this(data, citations, inferences, Status.SUCCEEDED, null);
    }

    public static AgentToolResult failed(String code, String message, boolean retryable) {
        return new AgentToolResult(null, List.of(), List.of(), Status.FAILED,
                new ToolError(code, message, retryable));
    }

    public AgentToolResult {
        if (status == null) status = Status.SUCCEEDED;
        if (status != Status.SUCCEEDED) {
            data = null;
            citations = List.of();
            inferences = List.of();
        }
        citations = citations == null ? List.of() : List.copyOf(citations);
        inferences = inferences == null ? List.of() : List.copyOf(inferences);
    }
}
