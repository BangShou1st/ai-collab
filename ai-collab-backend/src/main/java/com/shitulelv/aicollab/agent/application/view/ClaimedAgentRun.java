package com.shitulelv.aicollab.agent.application.view;

import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;

import java.util.UUID;

public record ClaimedAgentRun(
        UUID id,
        UUID sessionId,
        UUID projectId,
        UUID requesterId,
        UUID parentRunId,
        String role,
        int depth,
        String goal,
        AgentRunStatus previousStatus,
        boolean scheduled,
        boolean correctionAttempted,
        int version) {
}
