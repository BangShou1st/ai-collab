package com.shitulelv.aicollab.agent.domain.tool;

import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;

import java.util.UUID;

public record AgentToolContext(
        UUID runId,
        UUID projectId,
        UUID userId,
        String role,
        boolean scheduled,
        int depth,
        UUID invocationId,
        String userGoal,
        AgentPageContext page) {
    public AgentToolContext {
        if (page == null) {
            page = AgentPageContext.empty();
        }
    }

    public AgentToolContext(UUID runId, UUID projectId, UUID userId, String role, boolean scheduled, int depth) {
        this(runId, projectId, userId, role, scheduled, depth, null, null, null);
    }

    public AgentToolContext(UUID runId, UUID projectId, UUID userId, String role,
                            boolean scheduled, int depth, UUID invocationId) {
        this(runId, projectId, userId, role, scheduled, depth, invocationId, null, null);
    }

    public AgentToolContext(UUID runId, UUID projectId, UUID userId, String role,
                            boolean scheduled, int depth, UUID invocationId, String userGoal) {
        this(runId, projectId, userId, role, scheduled, depth, invocationId, userGoal, null);
    }

    public AgentToolContext withInvocationId(UUID id) {
        return new AgentToolContext(runId, projectId, userId, role, scheduled, depth, id, userGoal, page);
    }

    public AgentToolContext withPage(AgentPageContext nextPage) {
        return new AgentToolContext(runId, projectId, userId, role, scheduled, depth, invocationId, userGoal, nextPage);
    }
}
