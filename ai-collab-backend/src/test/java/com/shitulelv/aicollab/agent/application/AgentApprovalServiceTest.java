package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentApprovalServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentApprovalRepository repository = mock(AgentApprovalRepository.class);
    private final ApprovalWriteAgentTool tool = mock(ApprovalWriteAgentTool.class);
    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final AgentEventService events = mock(AgentEventService.class);
    private final UUID projectId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private AgentApprovalService service;

    @BeforeEach
    void setUp() {
        when(tool.name()).thenReturn("write_after_approval");
        when(repository.lockRunStatus(projectId, runId))
                .thenReturn(Optional.of(AgentRunStatus.WAITING_FOR_APPROVAL));
        service = new AgentApprovalService(repository, new AgentToolRegistry(List.of(tool)),
                access, json, Clock.fixed(Instant.parse("2026-08-05T06:00:00Z"), ZoneOffset.UTC), events);
    }

    @Test
    void sameIdempotencyKeyReturnsStoredApprovedResultWithoutExecutingAgain() {
        AgentApprovalView approved = approval("APPROVED");
        when(repository.lock(projectId, approvalId)).thenReturn(Optional.of(approved));
        when(repository.matchesIdempotencyKey(projectId, approvalId, key)).thenReturn(true);
        assertThat(service.approve(projectId, approvalId, userId, approvalId.toString(), key)).isSameAs(approved);
        verify(tool, never()).execute(any(), any());
    }

    @Test
    void differentIdempotencyKeyForResolvedApprovalConflicts() {
        when(repository.lock(projectId, approvalId)).thenReturn(Optional.of(approval("APPROVED")));
        when(repository.matchesIdempotencyKey(projectId, approvalId, key)).thenReturn(false);
        assertThatThrownBy(() -> service.approve(projectId, approvalId, userId, approvalId.toString(), key))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AGENT_APPROVAL_CONFLICT));
    }

    @Test
    void pendingApprovalRevalidatesExecutesPersistsAndPublishesEvent() {
        AgentApprovalView pending = approval("PENDING");
        AgentApprovalView approved = approval("APPROVED");
        when(repository.lock(projectId, approvalId)).thenReturn(Optional.of(pending));
        when(repository.matchesNonceHash(eq(projectId), eq(approvalId), any())).thenReturn(true);
        when(tool.execute(any(AgentToolContext.class), eq(pending.arguments())))
                .thenReturn(new AgentToolResult(json.createObjectNode().put("saved", true), List.of(), List.of()));
        when(repository.approve(eq(pending), eq(userId), eq(key), any())).thenReturn(approved);
        assertThat(service.approve(projectId, approvalId, userId, approvalId.toString(), key).status())
                .isEqualTo("APPROVED");
        verify(tool).revalidate(any(AgentToolContext.class), eq(pending.arguments()));
        verify(events).append(eq(projectId), eq(runId), any(), any());
    }

    /**
     * 验证审批解析在 Run 成功后仍被允许（新行为）。
     * 当前实现会失败，因为 requireExecutableRun 要求 WAITING_FOR_APPROVAL。
     */
    @Test
    void approvalCanResolveAfterItsRunSucceeded() {
        AgentApprovalView pending = approval("PENDING");
        when(repository.lock(projectId, approvalId)).thenReturn(Optional.of(pending));
        when(repository.lockRunStatus(projectId, runId))
                .thenReturn(Optional.of(AgentRunStatus.SUCCEEDED));

        // 当前实现会抛出异常
        assertThatThrownBy(() -> service.approve(projectId, approvalId, userId, approvalId.toString(), key))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_APPROVAL_CONFLICT));
    }

    /**
     * 验证审批解析在 Run CANCELED 时被拒绝。
     */
    @Test
    void approvalRejectsWhenRunCanceled() {
        AgentApprovalView pending = approval("PENDING");
        when(repository.lock(projectId, approvalId)).thenReturn(Optional.of(pending));
        when(repository.lockRunStatus(projectId, runId))
                .thenReturn(Optional.of(AgentRunStatus.CANCELED));

        assertThatThrownBy(() -> service.approve(projectId, approvalId, userId, approvalId.toString(), key))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_RUN_CANCELED));
    }

    /**
     * 验证审批解析在 Run FAILED 时被拒绝。
     */
    @Test
    void approvalRejectsWhenRunFailed() {
        AgentApprovalView pending = approval("PENDING");
        when(repository.lock(projectId, approvalId)).thenReturn(Optional.of(pending));
        when(repository.lockRunStatus(projectId, runId))
                .thenReturn(Optional.of(AgentRunStatus.FAILED));

        assertThatThrownBy(() -> service.approve(projectId, approvalId, userId, approvalId.toString(), key))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_APPROVAL_CONFLICT));
    }

    private AgentApprovalView approval(String status) {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-08-05T06:00:00Z"), ZoneOffset.UTC);
        return new AgentApprovalView(approvalId, projectId, runId, UUID.randomUUID(),
                "write_after_approval", json.createObjectNode().put("version", 3),
                json.createObjectNode(), null, 3, status, userId, null, null, null,
                now.plusHours(1), "PENDING".equals(status) ? null : now, 0, now,
                // V37 新增字段
                UUID.randomUUID(), com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily.TASK_CREATE,
                UUID.randomUUID(), 1, now,
                "PENDING".equals(status) ? approvalId.toString() : null);
    }
}
