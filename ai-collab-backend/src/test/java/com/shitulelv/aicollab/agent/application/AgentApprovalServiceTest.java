package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
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
        AgentApprovalView approved = approval("APPROVED");
        when(repository.lock(projectId, approvalId)).thenReturn(Optional.of(pending));
        when(repository.lockRunStatus(projectId, runId))
                .thenReturn(Optional.of(AgentRunStatus.SUCCEEDED));
        when(repository.matchesNonceHash(eq(projectId), eq(approvalId), any())).thenReturn(true);
        when(tool.execute(any(AgentToolContext.class), eq(pending.arguments())))
                .thenReturn(new AgentToolResult(json.createObjectNode().put("saved", true), List.of(), List.of()));
        when(repository.approve(eq(pending), eq(userId), eq(key), any())).thenReturn(approved);

        assertThat(service.approve(projectId, approvalId, userId, approvalId.toString(), key).status())
                .isEqualTo("APPROVED");
        verify(tool).revalidate(any(AgentToolContext.class), eq(pending.arguments()));
    }

    @Test
    void explicitProposalRevisionMergesPatchAndStripsReservedApprovalId() {
        UUID sessionId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        AgentRunView run = mock(AgentRunView.class);
        when(run.id()).thenReturn(runId);
        when(run.projectId()).thenReturn(projectId);
        when(run.sessionId()).thenReturn(sessionId);
        when(run.requesterId()).thenReturn(userId);

        JsonNode current = json.createObjectNode()
                .put("title", "修复登录页白屏")
                .put("assigneeId", UUID.randomUUID().toString())
                .put("dueDate", "2026-08-09");
        AgentApprovalView existing = mock(AgentApprovalView.class);
        when(existing.id()).thenReturn(existingId);
        when(existing.projectId()).thenReturn(projectId);
        when(existing.sessionId()).thenReturn(sessionId);
        when(existing.requesterId()).thenReturn(userId);
        when(existing.status()).thenReturn("PENDING");
        when(existing.proposalFamily()).thenReturn(AgentProposalFamily.TASK_CREATE);
        when(existing.arguments()).thenReturn(current);
        when(repository.find(projectId, existingId)).thenReturn(Optional.of(existing));
        when(repository.revise(eq(existing), any(), any(), any(), eq(runId))).thenReturn(existing);

        ApprovalWriteAgentTool revisionTool = passThroughTool(AgentProposalFamily.TASK_CREATE);
        AgentApprovalService revisionService = new AgentApprovalService(
                repository, new AgentToolRegistry(List.of(revisionTool)), access, json,
                Clock.fixed(Instant.parse("2026-08-05T06:00:00Z"), ZoneOffset.UTC), events);
        UUID newAssignee = UUID.randomUUID();
        JsonNode patch = json.createObjectNode()
                .put("approvalId", existingId.toString())
                .put("assigneeId", newAssignee.toString());

        AgentProposalOutcome outcome = revisionService.proposeOrRevise(
                run, turn(patch), new ModelToolCall("call-1", revisionTool.name(), patch),
                new AgentToolContext(runId, projectId, userId, "MEMBER", false, 0), revisionTool);

        assertThat(outcome.operation()).isEqualTo(AgentProposalOutcome.Operation.UPDATED);
        assertThat(outcome.currentArguments().path("title").asText()).isEqualTo("修复登录页白屏");
        assertThat(outcome.currentArguments().path("dueDate").asText()).isEqualTo("2026-08-09");
        assertThat(outcome.currentArguments().path("assigneeId").asText()).isEqualTo(newAssignee.toString());
        assertThat(outcome.currentArguments().has("approvalId")).isFalse();
    }

    @Test
    void updateTaskToolRevisesPendingTaskCreateProposal() {
        UUID sessionId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        AgentRunView run = proposalRun(sessionId);
        JsonNode current = json.createObjectNode()
                .put("title", "测试")
                .put("dueDate", "2026-08-08")
                .putNull("assigneeId");
        AgentApprovalView existing = pendingProposal(
                existingId, sessionId, "create_task_after_approval",
                AgentProposalFamily.TASK_CREATE, current);
        when(repository.find(projectId, existingId)).thenReturn(Optional.of(existing));
        when(repository.revise(eq(existing), any(), any(), any(), eq(runId))).thenReturn(existing);

        ApprovalWriteAgentTool createTool = namedPassThroughTool(
                "create_task_after_approval", AgentProposalFamily.TASK_CREATE);
        ApprovalWriteAgentTool updateTool = namedPassThroughTool(
                "update_task_after_approval", AgentProposalFamily.TASK_UPDATE);
        AgentApprovalService revisionService = new AgentApprovalService(
                repository, new AgentToolRegistry(List.of(createTool, updateTool)), access, json,
                Clock.fixed(Instant.parse("2026-08-05T06:00:00Z"), ZoneOffset.UTC), events);
        UUID newAssignee = UUID.randomUUID();
        JsonNode arguments = json.createObjectNode()
                .put("approvalId", existingId.toString())
                .set("changes", json.createObjectNode().put("assigneeId", newAssignee.toString()));

        AgentProposalOutcome outcome = revisionService.proposeOrRevise(
                run, turn(arguments), new ModelToolCall("call-update", updateTool.name(), arguments),
                new AgentToolContext(runId, projectId, userId, "MEMBER", false, 0), updateTool);

        assertThat(outcome.operation()).isEqualTo(AgentProposalOutcome.Operation.UPDATED);
        assertThat(outcome.approval()).isSameAs(existing);
        assertThat(outcome.currentArguments().path("title").asText()).isEqualTo("测试");
        assertThat(outcome.currentArguments().path("dueDate").asText()).isEqualTo("2026-08-08");
        assertThat(outcome.currentArguments().path("assigneeId").asText()).isEqualTo(newAssignee.toString());
        assertThat(outcome.currentArguments().has("changes")).isFalse();
    }

    @Test
    void unrelatedUpdateToolCannotRevisePendingTaskCreateProposal() {
        UUID sessionId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        AgentRunView run = proposalRun(sessionId);
        AgentApprovalView existing = pendingProposal(
                existingId, sessionId, "create_task_after_approval",
                AgentProposalFamily.TASK_CREATE, json.createObjectNode().put("title", "测试"));
        when(repository.find(projectId, existingId)).thenReturn(Optional.of(existing));
        ApprovalWriteAgentTool createTool = namedPassThroughTool(
                "create_task_after_approval", AgentProposalFamily.TASK_CREATE);
        ApprovalWriteAgentTool milestoneUpdateTool = namedPassThroughTool(
                "update_milestone_after_approval", AgentProposalFamily.MILESTONE_UPDATE);
        AgentApprovalService revisionService = new AgentApprovalService(
                repository, new AgentToolRegistry(List.of(createTool, milestoneUpdateTool)), access, json,
                Clock.fixed(Instant.parse("2026-08-05T06:00:00Z"), ZoneOffset.UTC), events);
        JsonNode arguments = json.createObjectNode()
                .put("approvalId", existingId.toString())
                .set("changes", json.createObjectNode().put("title", "错误修改"));

        assertThatThrownBy(() -> revisionService.proposeOrRevise(
                run, turn(arguments), new ModelToolCall("call-wrong", milestoneUpdateTool.name(), arguments),
                new AgentToolContext(runId, projectId, userId, "MEMBER", false, 0), milestoneUpdateTool))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AGENT_APPROVAL_CONFLICT));
        verify(repository, never()).revise(any(), any(), any(), any(), any());
    }

    @Test
    void completeCreateWithoutApprovalIdNeverOverwritesAnotherPendingProposal() {
        AgentRunView run = mock(AgentRunView.class);
        when(run.id()).thenReturn(runId);
        when(run.projectId()).thenReturn(projectId);
        when(run.sessionId()).thenReturn(UUID.randomUUID());
        when(run.requesterId()).thenReturn(userId);
        ApprovalWriteAgentTool createTool = passThroughTool(AgentProposalFamily.TASK_CREATE);
        AgentApprovalService createService = new AgentApprovalService(
                repository, new AgentToolRegistry(List.of(createTool)), access, json,
                Clock.fixed(Instant.parse("2026-08-05T06:00:00Z"), ZoneOffset.UTC), events);
        JsonNode arguments = json.createObjectNode().put("title", "另一个独立任务");
        AgentApprovalView created = mock(AgentApprovalView.class);
        when(repository.createProposal(any(), eq(run), any(), any(), eq(arguments), any(), any(), any(), any()))
                .thenReturn(created);

        AgentProposalOutcome outcome = createService.proposeOrRevise(
                run, turn(arguments), new ModelToolCall("call-2", createTool.name(), arguments),
                new AgentToolContext(runId, projectId, userId, "MEMBER", false, 0), createTool);

        assertThat(outcome.operation()).isEqualTo(AgentProposalOutcome.Operation.CREATED);
        verify(repository, never()).findCompatiblePending(any(), any(), any(), any());
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

    private ApprovalWriteAgentTool passThroughTool(AgentProposalFamily family) {
        return namedPassThroughTool("create_task_after_approval", family);
    }

    private ApprovalWriteAgentTool namedPassThroughTool(String name, AgentProposalFamily family) {
        return new ApprovalWriteAgentTool() {
            @Override public String name() { return name; }
            @Override public AgentProposalFamily proposalFamily() { return family; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public JsonNode normalize(AgentToolContext context, JsonNode arguments) { return arguments; }
            @Override public JsonNode diff(AgentToolContext context, JsonNode arguments) {
                return json.createObjectNode().set("after", arguments);
            }
            @Override public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
                throw new AssertionError("提案阶段不得执行写操作");
            }
        };
    }

    private AgentRunView proposalRun(UUID sessionId) {
        AgentRunView run = mock(AgentRunView.class);
        when(run.id()).thenReturn(runId);
        when(run.projectId()).thenReturn(projectId);
        when(run.sessionId()).thenReturn(sessionId);
        when(run.requesterId()).thenReturn(userId);
        return run;
    }

    private AgentApprovalView pendingProposal(
            UUID existingId, UUID sessionId, String toolName,
            AgentProposalFamily family, JsonNode arguments) {
        AgentApprovalView existing = mock(AgentApprovalView.class);
        when(existing.id()).thenReturn(existingId);
        when(existing.projectId()).thenReturn(projectId);
        when(existing.sessionId()).thenReturn(sessionId);
        when(existing.requesterId()).thenReturn(userId);
        when(existing.status()).thenReturn("PENDING");
        when(existing.toolName()).thenReturn(toolName);
        when(existing.proposalFamily()).thenReturn(family);
        when(existing.arguments()).thenReturn(arguments);
        return existing;
    }

    private ModelTurnResult turn(JsonNode arguments) {
        return new ModelTurnResult("", List.of(new ModelToolCall("call", "create_task_after_approval", arguments)),
                ModelFinishReason.TOOL_CALLS, null, "test", "model", 10L);
    }
}
