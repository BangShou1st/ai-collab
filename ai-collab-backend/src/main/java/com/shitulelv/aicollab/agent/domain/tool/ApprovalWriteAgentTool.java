package com.shitulelv.aicollab.agent.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface ApprovalWriteAgentTool extends AgentTool {
    JsonNode normalize(AgentToolContext context, JsonNode arguments);

    JsonNode diff(AgentToolContext context, JsonNode normalizedArguments);
}
