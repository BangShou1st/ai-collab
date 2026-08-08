package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentProposalOutcome;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.*;
import com.shitulelv.aicollab.agent.domain.policy.AgentLoopGuard;
import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AgentRuntimeCoordinator 测试。
 */
class AgentRuntimeCoordinatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private AgentRepository repository;
    private AgentContextAssembler contextAssembler;
    private AgentSkillRegistry skillRegistry;
    private AgentPlanService planService;
    private AgentToolRegistry tools;
    private AgentCancellationService cancellation;
    private AgentLoopGuard loopGuard;
    private AgentApprovalService approvals;
    private RoutingAgentModelExecutor modelExecutor;
    private AgentToolResultSanitizer sanitizer;
    private AgentRuntimeCoordinator coordinator;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        contextAssembler = mock(AgentContextAssembler.class);
        skillRegistry = new AgentSkillRegistry();
        planService = mock(AgentPlanService.class);
        tools = new AgentToolRegistry(List.of());
        cancellation = new AgentCancellationService(repository);
        loopGuard = new AgentLoopGuard();
        approvals = mock(AgentApprovalService.class);
        modelExecutor = mock(RoutingAgentModelExecutor.class);
        sanitizer = new AgentToolResultSanitizer(json);
        coordinator = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService,
                tools, cancellation, loopGuard, approvals, modelExecutor, sanitizer, json);

        // 设置默认返回值：recordToolResult 和 recordModelTurn 返回传入的 run
        when(repository.recordToolResult(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.recordModelTurn(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.recordFinal(any(), any(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void autoSelectsDefaultSkillWhenNoExplicitCode() {
        AgentRunView run = run();
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));

        ModelTurnResult turn = textResult("回答");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        coordinator.advance(run);

        verify(planService).ensurePlan(eq(run), argThat(skill ->
                "PROJECT_RESEARCH".equals(skill.code())));
    }

    @Test
    void usesExplicitSkillCode() {
        AgentRunView run = runWithSkillCode("WEEKLY_REPORT");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("WEEKLY_REPORT"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "WEEKLY_REPORT".equals(s.code()))))
                .thenReturn(plan("周报", List.of()));

        ModelTurnResult turn = textResult("周报内容");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo("周报内容");
    }

    @Test
    void rejectsUnknownSkillCode() {
        AgentRunView run = runWithSkillCode("NONEXISTENT");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("NONEXISTENT"), any())).thenReturn(ctx);

        assertThatThrownBy(() -> coordinator.advance(run))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill 不存在");
    }

    @Test
    void simpleTextResponseCompletesRun() {
        AgentRunView run = run();
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));

        ModelTurnResult turn = textResult("项目进展正常");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo("项目进展正常");
        verify(repository).recordModelTurn(run, turn);
        verify(repository).recordFinal(run, "项目进展正常", List.of());
    }

    @Test
    void toolCallEntersToolExecutor() {
        AgentRunView run = run();
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));

        // 注册一个只读工具
        AgentTool tool = readOnlyTool("list_tasks");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        coordinator = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService,
                registry, cancellation, loopGuard, approvals, modelExecutor, sanitizer, json);

        ModelToolCall tc = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult turn1 = toolCallResult(tc);

        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // 工具执行完成后，重新排队等待下一轮
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(modelExecutor, times(1)).callModel(eq(run), any(), any(), eq(false));
        verify(repository).requeueRun(run);
    }

    @Test
    void writeToolProposalKeepsModelCompletionMetadata() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("write", List.of()));

        ApprovalWriteAgentTool tool = new ApprovalWriteAgentTool() {
            @Override public String name() { return "create_task_after_approval"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
                throw new AssertionError("write tool must wait for approval");
            }
            @Override public JsonNode normalize(AgentToolContext context, JsonNode arguments) { return arguments; }
            @Override public JsonNode diff(AgentToolContext context, JsonNode arguments) { return json.createObjectNode(); }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        coordinator = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService,
                registry, cancellation, loopGuard, approvals, modelExecutor, sanitizer, json);

        JsonNode proposalArguments = json.createObjectNode()
                .put("title", "修复登录页白屏")
                .put("dueDate", "2026-08-09")
                .put("priority", "HIGH");
        ModelToolCall call = new ModelToolCall(
                "call-write", "create_task_after_approval", proposalArguments);
        ModelTurnResult turn = toolCallResult(call);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);
        AgentApprovalView approval = mock(AgentApprovalView.class);
        when(approval.id()).thenReturn(UUID.randomUUID());
        when(approval.proposalFamily()).thenReturn(AgentProposalFamily.TASK_CREATE);
        when(approval.revision()).thenReturn(1);
        when(approval.arguments()).thenReturn(call.arguments());
        when(approvals.proposeOrRevise(eq(run), eq(turn), eq(call), any(), eq(tool)))
                .thenReturn(new AgentProposalOutcome(approval, AgentProposalOutcome.Operation.CREATED,
                        json.createObjectNode(), call.arguments(), json.createObjectNode()));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer())
                .contains("修复登录页白屏")
                .contains("截止日期：2026-08-09")
                .contains("优先级：HIGH")
                .contains("待审批");
        verify(approvals).proposeOrRevise(eq(run), argThat(modelTurn ->
                        modelTurn.provider().equals("test")
                                && modelTurn.model().equals("model")
                                && modelTurn.latencyMs() == 100L),
                  eq(call), any(), eq(tool));
    }

    @Test
    void writeProposalFailureTerminatesRunImmediatelyInsteadOfWaitingForLeaseExpiry() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("write", List.of()));

        ApprovalWriteAgentTool tool = new ApprovalWriteAgentTool() {
            @Override public String name() { return "create_task_after_approval"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
                throw new AssertionError("write tool must wait for approval");
            }
            @Override public JsonNode normalize(AgentToolContext context, JsonNode arguments) { return arguments; }
            @Override public JsonNode diff(AgentToolContext context, JsonNode arguments) { return json.createObjectNode(); }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        coordinator = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService,
                registry, cancellation, loopGuard, approvals, modelExecutor, sanitizer, json);

        ModelToolCall call = new ModelToolCall(
                "call-write-failure", "create_task_after_approval",
                json.createObjectNode().put("title", "修复登录页面白屏问题"));
        ModelTurnResult turn = toolCallResult(call);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);
        when(approvals.proposeOrRevise(eq(run), eq(turn), eq(call), any(), eq(tool)))
                .thenThrow(new IllegalArgumentException("invalid proposal"));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_TOOL_EXECUTION_FAILED");
        verify(repository).recordFailure(run, "AGENT_TOOL_EXECUTION_FAILED", false);
        verify(repository, never()).requeueRun(any());
    }

    @Test
    void trustedPendingProposalIsInjectedWithIdAndLatestArguments() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        UUID approvalId = UUID.randomUUID();
        var proposal = new AgentProposalContext(
                approvalId, AgentProposalFamily.TASK_CREATE, UUID.randomUUID(), "PENDING", 2,
                json.createObjectNode().put("title", "修复登录页白屏").put("dueDate", "2026-08-09"),
                null, json.createObjectNode().put("assigneeId", "changed"));
        AgentExecutionContext base = context();
        AgentExecutionContext ctx = new AgentExecutionContext(
                base.runId(), base.sessionId(), base.projectId(), base.requesterId(),
                base.projectRole(), base.scheduled(), base.page(), base.limits(), base.depth(), List.of(proposal));
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("修订任务提案", List.of()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(textResult("已处理"));

        coordinator.advance(run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), messages.capture(), any(), eq(false));
        assertThat(messages.getValue())
                .filteredOn(ModelMessage.System.class::isInstance)
                .map(ModelMessage.System.class::cast)
                .extracting(ModelMessage.System::content)
                .anyMatch(content -> content.contains("TRUSTED_PROPOSALS")
                        && content.contains(approvalId.toString())
                        && content.contains("修复登录页白屏")
                        && content.contains("最新需求优先"));
    }

    @Test
    void cancelRequestDoesNotCompleteSuccessfully() {
        AgentRunView run = runWithStatus(AgentRunStatus.CANCELED);
        // cancellation checks run.status() directly, no need to mock contextAssembler

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(outcome.errorCode()).isEqualTo("RUN_CANCELLED");
        verify(repository).recordCanceled(run);
    }

    @Test
    void coordinatorErrorPropagatesWithoutWorkerOverride() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any()))
                .thenThrow(new BusinessException(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID));

        assertThatThrownBy(() -> coordinator.advance(run))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID));
    }

    @Test
    void normalRequestDoesNotEnterOldWorkerLoop() {
        // 验证 Coordinator 是独立处理，不依赖 AgentWorker 的旧循环
        AgentRunView run = run();
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));

        ModelTurnResult turn = textResult("完成");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        // 验证走的是 Coordinator 路径（有 recordModelTurn）
        verify(repository).recordModelTurn(run, turn);
        // 不应该有 requeue（除非有工具调用）
        verify(repository, never()).requeueRun(any());
    }

    @Test
    void promptKeepsRootListingAtRequestedDepth() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("读取仓库根目录", List.of()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false)))
                .thenReturn(textResult("完成"));

        coordinator.advance(run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), messages.capture(), any(), eq(false));
        assertThat(messages.getValue())
                .filteredOn(ModelMessage.System.class::isInstance)
                .map(ModelMessage.System.class::cast)
                .extracting(ModelMessage.System::content)
                .anyMatch(content -> content.contains("只要求根目录、当前层或列表时，不得读取子目录或文件正文"));
    }

    @Test
    void finalBoundaryUsesToolFreeModelTurnAndCompletesFromExistingEvidence() {
        AgentRunView run = runAtUsage(14, 5);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("列出根目录", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(modelExecutor.callModel(eq(run), any(), anyList(), eq(false)))
                .thenReturn(textResult("根目录包含 docs、scripts 等。"));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> definitions = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), messages.capture(), definitions.capture(), eq(false));
        assertThat(definitions.getValue()).isEmpty();
        assertThat(messages.getValue())
                .filteredOn(ModelMessage.System.class::isInstance)
                .map(ModelMessage.System.class::cast)
                .extracting(ModelMessage.System::content)
                .anyMatch(content -> content.contains("只能基于已经取得的工具结果"));
        verify(repository, never()).requeueRun(any());
    }

    @Test
    void finalBoundaryFallsBackToPersistedEvidenceWhenModelCallsAnotherTool() {
        AgentRunView run = runAtUsage(14, 5);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("列出根目录", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(modelExecutor.callModel(eq(run), any(), anyList(), eq(false)))
                .thenReturn(toolCallResult(new ModelToolCall(
                        "call-extra", "list_tasks", json.createObjectNode())));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).contains("已取得的结果").contains("mcp.github-readonly.get_file_contents");
        verify(repository).recordFinal(any(), contains("已取得的结果"), anyList());
        verify(repository, never()).recordToolResult(any(), any(), any(), any(), anyBoolean());
        verify(repository, never()).requeueRun(any());
    }

    @Test
    void exhaustedRunReturnsPersistedEvidenceWithoutAnotherModelCall() {
        AgentRunView run = runAtUsage(15, 5);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("列出根目录", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).contains("已取得的结果").contains("type");
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean());
        verify(repository, never()).recordBudgetExceeded(any());
    }

    @Test
    void rejectsToolBatchAbovePerTurnBudgetBeforeExecution() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any()))
                .thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("规划", List.of()));
        ModelToolCall[] calls = new ModelToolCall[5];
        for (int index = 0; index < calls.length; index++) {
            calls[index] = new ModelToolCall(
                    "call-" + index, "list_tasks", json.createObjectNode());
        }
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false)))
                .thenReturn(toolCallResult(calls));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(repository).recordBudgetExceeded(any());
        verify(repository, never()).recordToolResult(any(), any(), any(), any(), anyBoolean());
        verify(repository, never()).requeueRun(any());
    }

    // ========== 辅助方法 ==========

    private AgentRunView run() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }

    @Test
    void toolResultIsSanitizedBeforeRecording() {
        // 使用 ITERATION_PLANNING skill，它允许 list_tasks
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 创建一个返回敏感数据的工具（模拟 list_tasks 返回敏感数据）
        AgentTool sensitiveTool = new AgentTool() {
            @Override public String name() { return "list_tasks"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                ObjectNode data = json.createObjectNode();
                data.put("password", "secret123");
                data.put("token", "abc-def-ghi");
                data.put("normal", "safe");
                return new AgentToolResult(data, null, null);
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(sensitiveTool));
        coordinator = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService,
                registry, cancellation, loopGuard, approvals, modelExecutor, sanitizer, json);

        ModelToolCall tc = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult turn1 = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // 验证记录的工具结果被清洗过（密码和 token 被替换为 [REDACTED]）
        verify(repository).recordToolResult(eq(run), eq("list_tasks"), any(),
                argThat(result -> {
                    String resultStr = result.toString();
                    return resultStr.contains("[REDACTED]")
                            && !resultStr.contains("secret123")
                            && !resultStr.contains("abc-def-ghi");
                }), eq(false));
    }

    private AgentRunView runWithSkillCode(String skillCode) {
        AgentRunView r = run();
        return new AgentRunView(
                r.id(), r.sessionId(), r.projectId(), r.requesterId(),
                r.parentRunId(), r.role(), r.depth(), r.goal(), r.status(),
                r.maxSteps(), r.maxToolCalls(), r.maxChildren(),
                r.maxInputTokens(), r.maxOutputTokens(),
                r.stepsUsed(), r.toolCallsUsed(), r.childrenUsed(),
                r.inputTokensUsed(), r.outputTokensUsed(),
                r.tokenUsageEstimated(), r.scheduled(), r.correctionAttempted(),
                r.retryCount(), r.errorCode(), r.planJson(), r.pageContextJson(), skillCode,
                r.version(), r.createdAt(), r.updatedAt());
    }

    private AgentRunView runWithStatus(AgentRunStatus status) {
        AgentRunView r = run();
        return new AgentRunView(
                r.id(), r.sessionId(), r.projectId(), r.requesterId(),
                r.parentRunId(), r.role(), r.depth(), r.goal(), status,
                r.maxSteps(), r.maxToolCalls(), r.maxChildren(),
                r.maxInputTokens(), r.maxOutputTokens(),
                r.stepsUsed(), r.toolCallsUsed(), r.childrenUsed(),
                r.inputTokensUsed(), r.outputTokensUsed(),
                r.tokenUsageEstimated(), r.scheduled(), r.correctionAttempted(),
                r.retryCount(), r.errorCode(), r.planJson(), r.pageContextJson(), r.skillCode(),
                r.version(), r.createdAt(), r.updatedAt());
    }

    private AgentRunView runAtUsage(int stepsUsed, int toolCallsUsed) {
        AgentRunView r = run();
        return new AgentRunView(
                r.id(), r.sessionId(), r.projectId(), r.requesterId(),
                r.parentRunId(), r.role(), r.depth(), r.goal(), r.status(),
                r.maxSteps(), r.maxToolCalls(), r.maxChildren(),
                r.maxInputTokens(), r.maxOutputTokens(),
                stepsUsed, toolCallsUsed, r.childrenUsed(),
                r.inputTokensUsed(), r.outputTokensUsed(),
                r.tokenUsageEstimated(), r.scheduled(), r.correctionAttempted(),
                r.retryCount(), r.errorCode(), r.planJson(), r.pageContextJson(), r.skillCode(),
                r.version(), r.createdAt(), r.updatedAt());
    }

    private AgentStepView successfulToolStep() {
        return new AgentStepView(
                UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "mcp.github-readonly.get_file_contents", json.createObjectNode(),
                json.createObjectNode().put("type", "dir"), "TOOL_SUCCESS",
                null, null, false, null, null, OffsetDateTime.now());
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", false, AgentPageContext.empty(),
                AgentRuntimeLimits.defaults(), 0, List.of());
    }

    private AgentPlan plan(String objective, List<AgentPlanStep> steps) {
        return AgentPlan.create(objective, steps);
    }

    private ModelTurnResult textResult(String content) {
        return new ModelTurnResult(content, List.of(), ModelFinishReason.STOP, null, "test", "model", 100L);
    }

    private ModelTurnResult toolCallResult(ModelToolCall... calls) {
        return new ModelTurnResult("", List.of(calls), ModelFinishReason.TOOL_CALLS, null, "test", "model", 100L);
    }

    private AgentTool readOnlyTool(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext ctx, com.fasterxml.jackson.databind.JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
        };
    }
}
