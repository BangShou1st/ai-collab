package com.shitulelv.aicollab.agent.api.dto;

import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.common.api.JsonApiValue;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentApprovalResponse(
        UUID id,
        UUID projectId,
        UUID runId,
        UUID stepId,
        String toolName,
        Object arguments,
        Object diff,
        UUID resourceId,
        Integer resourceVersion,
        String status,
        UUID requesterId,
        UUID approverId,
        Object result,
        String rejectionReason,
        OffsetDateTime expiresAt,
        OffsetDateTime resolvedAt,
        int version,
        OffsetDateTime createdAt,
        String nonce) {

    public static AgentApprovalResponse from(AgentApprovalView view) {
        return new AgentApprovalResponse(
                view.id(), view.projectId(), view.runId(), view.stepId(), view.toolName(),
                JsonApiValue.from(view.arguments()), JsonApiValue.from(view.diff()),
                view.resourceId(), view.resourceVersion(), view.status(), view.requesterId(),
                view.approverId(), JsonApiValue.from(view.result()), view.rejectionReason(),
                view.expiresAt(), view.resolvedAt(), view.version(), view.createdAt(), view.nonce());
    }
}
