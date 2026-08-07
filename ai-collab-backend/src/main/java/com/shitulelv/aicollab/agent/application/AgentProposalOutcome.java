package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;

/**
 * 提案创建或修订的结果。
 */
public record AgentProposalOutcome(
    AgentApprovalView approval,
    Operation operation,
    JsonNode previousArguments,
    JsonNode currentArguments,
    JsonNode diff
) {
    public enum Operation {
        CREATED,
        UPDATED
    }
}
