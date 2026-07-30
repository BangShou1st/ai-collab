package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.ResolveAgentApprovalRequest;
import com.shitulelv.aicollab.agent.api.dto.AgentApprovalResponse;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/approvals")
public class AgentApprovalController {
    private final AgentApprovalService approvals;

    public AgentApprovalController(AgentApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public ApiResponse<List<AgentApprovalResponse>> list(
            @PathVariable UUID projectId, @RequestParam(required = false) String status,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(approvals.list(projectId, userId(jwt), status)
                .stream().map(AgentApprovalResponse::from).toList());
    }

    @PostMapping("/{approvalId}/approve")
    public ApiResponse<AgentApprovalResponse> approve(
            @PathVariable UUID projectId, @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody ResolveAgentApprovalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(AgentApprovalResponse.from(approvals.approve(
                projectId, approvalId, userId(jwt), request.nonce(), idempotencyKey)));
    }

    @PostMapping("/{approvalId}/reject")
    public ApiResponse<AgentApprovalResponse> reject(
            @PathVariable UUID projectId, @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody ResolveAgentApprovalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(AgentApprovalResponse.from(approvals.reject(
                projectId, approvalId, userId(jwt), request.nonce(),
                idempotencyKey, request.reason())));
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
