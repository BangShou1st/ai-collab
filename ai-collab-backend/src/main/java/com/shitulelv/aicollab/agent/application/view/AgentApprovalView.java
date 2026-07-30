package com.shitulelv.aicollab.agent.application.view;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentApprovalView(
        UUID id,
        UUID projectId,
        UUID runId,
        UUID stepId,
        String toolName,
        JsonNode arguments,
        JsonNode diff,
        UUID resourceId,
        Integer resourceVersion,
        String status,
        UUID requesterId,
        UUID approverId,
        JsonNode result,
        String rejectionReason,
        OffsetDateTime expiresAt,
        OffsetDateTime resolvedAt,
        int version,
        OffsetDateTime createdAt,
        String nonce) {
}
