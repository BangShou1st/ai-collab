package com.shitulelv.aicollab.agent.domain.tool;

import java.util.UUID;

public record AgentToolContext(
        UUID runId,
        UUID projectId,
        UUID userId,
        String role,
        boolean scheduled,
        int depth) {
}
