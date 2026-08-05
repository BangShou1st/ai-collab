package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.policy.AgentApprovalPolicy;
import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AgentApprovalService {
    private static final Logger log = LoggerFactory.getLogger(AgentApprovalService.class);

    private final AgentApprovalRepository approvals;
    private final AgentToolRegistry tools;
    private final ProjectAccessGuard access;
    private final ObjectMapper json;
    private final Clock clock;
    private final AgentEventService events;
    private final AgentApprovalPolicy policy = new AgentApprovalPolicy();

    public AgentApprovalService(
            AgentApprovalRepository approvals, AgentToolRegistry tools,
            ProjectAccessGuard access, ObjectMapper json, Clock clock, AgentEventService events) {
        this.approvals = approvals;
        this.tools = tools;
        this.access = access;
        this.json = json;
        this.clock = clock;
        this.events = events;
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

    /**
     * 执行审批。执行前必须重新校验：
     * 1. Approval 存在
     * 2. Approval 属于当前项目
     * 3. Approval 尚未处理
     * 4. nonce 或审批凭证正确
     * 5. 当前审批操作者仍有权限
     * 6. 目标实体仍存在
     * 7. 目标实体 version 与提案时一致
     * 8. 业务前置条件仍然成立
     * 9. 规范化参数仍然有效
     */
    @Transactional
    public AgentApprovalView approve(
            UUID projectId, UUID approvalId, UUID approverId,
            String nonce, UUID idempotencyKey) {
        // 1. Approval 存在 + 2. 属于当前项目
        access.requireAdmin(projectId, approverId);
        AgentApprovalView approval = requireLocked(projectId, approvalId);

        // 3. Approval 尚未处理 + 幂等检查
        if ("APPROVED".equals(approval.status())) {
            if (approvals.matchesIdempotencyKey(projectId, approvalId, idempotencyKey)) {
                return approval;
            }
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_CONFLICT);
        }
        requireExecutableRun(approval);

        // 4. nonce 正确
        requireResolvable(approval, nonce);

        // 5. 工具存在且可执行
        AgentTool found = tools.find(approval.toolName())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_APPROVAL_CONFLICT));
        if (!(found instanceof ApprovalWriteAgentTool writeTool)) {
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_CONFLICT);
        }

        // 6-9. 执行前重新校验（目标实体存在、版本匹配、业务前置条件、参数有效）
        AgentToolContext toolCtx = context(approval, approverId);
        try {
            writeTool.revalidate(toolCtx, approval.arguments());
        } catch (BusinessException e) {
            log.warn("审批执行前重校验失败: approvalId={}, errorCode={}", approvalId, e.getErrorCode());
            throw e;
        } catch (Exception e) {
            log.warn("审批执行前重校验异常: approvalId={}", approvalId, e);
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_REVALIDATION_FAILED,
                    "审批执行前重校验失败: " + e.getMessage());
        }

        // 执行写操作
        AgentToolResult result = writeTool.execute(toolCtx, approval.arguments());
        AgentApprovalView resolved = approvals.approve(
                approval, approverId, idempotencyKey, json.valueToTree(result));
        events.append(projectId, approval.runId(), AgentEventType.APPROVAL_APPROVED,
                json.createObjectNode().put("approvalId", approvalId.toString()));
        return resolved;
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
        requireExecutableRun(approval);
        requireResolvable(approval, nonce);
        AgentApprovalView resolved = approvals.reject(
                approval, approverId, idempotencyKey, reason == null ? "" : reason.trim());
        events.append(projectId, approval.runId(), AgentEventType.APPROVAL_REJECTED,
                json.createObjectNode().put("approvalId", approvalId.toString()));
        return resolved;
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

    private void requireExecutableRun(AgentApprovalView approval) {
        com.shitulelv.aicollab.agent.domain.model.AgentRunStatus status = approvals
                .lockRunStatus(approval.projectId(), approval.runId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        if (status == com.shitulelv.aicollab.agent.domain.model.AgentRunStatus.CANCELED) {
            throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED);
        }
        if (status != com.shitulelv.aicollab.agent.domain.model.AgentRunStatus.WAITING_FOR_APPROVAL) {
            throw new BusinessException(ErrorCode.AGENT_APPROVAL_CONFLICT);
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
