package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;

public record AgentWorkerOutcome(
        AgentRunStatus status,
        String answer,
        String toolName,
        String errorCode) {
}
