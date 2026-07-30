package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.policy.AgentApprovalPolicy;
import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AgentApprovalService {
    private final AgentApprovalRepository approvals;
    private final AgentToolRegistry tools;
    private final ProjectAccessGuard access;
    private final ObjectMapper json;
    private final Clock clock;
    private final AgentApprovalPolicy policy = new AgentApprovalPolicy();

    public AgentApprovalService(
            AgentApprovalRepository approvals, AgentToolRegistry tools,
            ProjectAccessGuard access, ObjectMapper json, Clock clock) {
        this.approvals = approvals;
        this.tools = tools;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    public AgentApprovalView propose(
            AgentRunView run, ChatCompletionResult completion,
            AgentDecision.CallTool call, AgentToolContext context,
            ApprovalWriteAgentTool tool) {
        JsonNode arguments = tool.normalize(context, call.arguments());
        JsonNode diff = tool.diff(context, arguments);
        UUID approvalId = UUID.randomUUID();
        return approvals.createProposal(
                approvalId, run, completion, call, arguments, diff,
                policy.nonceHash(canonical(arguments)),
                policy.nonceHash(approvalId.toString()),
                OffsetDateTime.now(clock).plus(Duration.ofHours(24)));
    }

    public List<AgentApprovalView> list(UUID projectId, UUID userId, String status) {
        access.requireMember(projectId, userId);
        return approvals.list(projectId, status);
    }

    @Transactional
    public AgentApprovalView approve(
            UUID projectId, UUID approvalId, UUID approverId,
            String nonce, UUID idempotencyKey) {
        access.requireAdmin(projectId, approverId);
        AgentApprovalView approval = requireLocked(projectId, approvalId);
        if ("APPROVED".equals(approval.status())) {
            if (approvals.matchesIdempotencyKey(projectId, approvalId, idempotencyKey)) {
                return approval;
            }
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_CONFLICT);
        }
        requireResolvable(approval, nonce);
        AgentTool found = tools.find(approval.toolName())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_APPROVAL_CONFLICT));
        if (!(found instanceof ApprovalWriteAgentTool writeTool)) {
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_CONFLICT);
        }
        AgentToolResult result = writeTool.execute(
                context(approval, approverId), approval.arguments());
        return approvals.approve(approval, approverId, idempotencyKey, json.valueToTree(result));
    }

    @Transactional
    public AgentApprovalView reject(
            UUID projectId, UUID approvalId, UUID approverId,
            String nonce, UUID idempotencyKey, String reason) {
        access.requireAdmin(projectId, approverId);
        AgentApprovalView approval = requireLocked(projectId, approvalId);
        if ("REJECTED".equals(approval.status())) {
            if (approvals.matchesIdempotencyKey(projectId, approvalId, idempotencyKey)) {
                return approval;
            }
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_CONFLICT);
        }
        requireResolvable(approval, nonce);
        return approvals.reject(
                approval, approverId, idempotencyKey, reason == null ? "" : reason.trim());
    }

    private AgentApprovalView requireLocked(UUID projectId, UUID approvalId) {
        return approvals.lock(projectId, approvalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_APPROVAL_NOT_FOUND));
    }

    private void requireResolvable(AgentApprovalView approval, String nonce) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            policy.requirePending(approval.status(), approval.expiresAt(), now);
        } catch (IllegalStateException exception) {
            throw new BusinessException(
                    approval.expiresAt() != null && !approval.expiresAt().isAfter(now)
                            ? ErrorCode.AGENT_APPROVAL_EXPIRED : ErrorCode.AGENT_APPROVAL_CONFLICT);
        }
        if (!approvals.matchesNonceHash(
                approval.projectId(), approval.id(), policy.nonceHash(nonce))) {
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_NONCE_INVALID);
        }
    }

    private static AgentToolContext context(AgentApprovalView approval, UUID approverId) {
        return new AgentToolContext(
                approval.runId(), approval.projectId(), approverId,
                "SUPERVISOR", false, 0);
    }

    private String canonical(JsonNode node) {
        try { return json.writeValueAsString(node); }
        catch (Exception exception) {
            throw new IllegalArgumentException("审批参数无法序列化", exception);
        }
    }
}
