package com.shitulelv.aicollab.agent.domain.tool;

import java.util.UUID;

public record AgentToolContext(
        UUID runId,
        UUID projectId,
        UUID userId,
        String role,
        boolean scheduled,
        int depth,
        UUID invocationId,
        String userGoal) {
    public AgentToolContext(UUID runId,UUID projectId,UUID userId,String role,boolean scheduled,int depth) {
        this(runId,projectId,userId,role,scheduled,depth,null,null);
    }
    public AgentToolContext(UUID runId, UUID projectId, UUID userId, String role,
                            boolean scheduled, int depth, UUID invocationId) {
        this(runId, projectId, userId, role, scheduled, depth, invocationId, null);
    }
    public AgentToolContext withInvocationId(UUID id) {
        return new AgentToolContext(runId, projectId, userId, role, scheduled, depth, id, userGoal);
    }
}
