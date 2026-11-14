package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentProposalOutcome;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.*;


import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AgentRuntimeCoordinator 测试。
 */
class AgentRuntimeCoordinatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private AgentRepository repository;
    private AgentContextAssembler contextAssembler;
    private AgentScenarioRegistry skillRegistry;
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
        skillRegistry = new AgentScenarioRegistry();
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
        when(repository.recordModelTurnWithSettlement(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.recordFinal(any(), any(), anyList(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // 请求准备快照：默认原生模式；Legacy 场景的用例自行覆盖
        when(modelExecutor.resolveRequest(any()))
                .thenReturn(AgentRuntimeBehaviorTest.resolved(true));
    }

    @Test
    void autoSelectsDefaultScenarioWhenNoExplicitCode() {
        AgentRunView run = run();
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));

        ModelTurnResult turn = textResult("回答");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(turn);

        coordinator.advance(run);

        verify(planService).ensurePlan(eq(run), argThat(skill ->
                "PROJECT_RESEARCH".equals(skill.code())));
    }

    @Test
    void usesExplicitScenarioCode() {
        AgentRunView run = runWithScenarioCode("WEEKLY_REPORT");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("WEEKLY_REPORT"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "WEEKLY_REPORT".equals(s.code()))))
                .thenReturn(plan("周报", List.of()));

        ModelTurnResult turn = textResult("周报内容");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo("周报内容");
    }

    @Test
    void rejectsUnknownScenarioCode() {
        AgentRunView run = runWithScenarioCode("NONEXISTENT");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("NONEXISTENT"), any())).thenReturn(ctx);

        assertThatThrownBy(() -> coordinator.advance(run))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scenario 不存在");
    }

    @Test
    void simpleTextResponseCompletesRun() {
        AgentRunView run = run();
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));

        ModelTurnResult turn = textResult("项目进展正常");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo("项目进展正常");
        verify(repository).recordModelTurnWithSettlement(eq(run), eq(turn), any(), eq("MODEL_TURN"), any(), any(), any());
        verify(repository).recordFinal(run, "项目进展正常", List.of(), true);
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

        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(turn1);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // 工具执行完成后，重新排队等待下一轮
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(modelExecutor, times(1)).callModel(eq(run), any(), any(), eq(false), any());
        verify(repository).requeueRun(run);
    }

    @Test
    void pythonDecideCallToolSkipsJavaModelAndEntersNormalToolExecutor() {
        AgentRunView run = run();
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));

        AgentToolRegistry registry = new AgentToolRegistry(List.of(readOnlyTool("list_tasks")));
        coordinator = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService,
                registry, cancellation, loopGuard, approvals, modelExecutor, sanitizer, json);
        DecideRouter router = mock(DecideRouter.class);
        ModelToolCall call = new ModelToolCall(
                "python-decide-call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult pythonTurn = toolCallResult(call);
        when(router.enabledForMainChain()).thenReturn(true);
        when(router.route(eq(run), any(), any(), any(), anyInt(), anyLong(), anyBoolean(), any()))
                .thenReturn(DecideRouter.Route.handled(
                        pythonTurn, "python:CALL_TOOL", "CALL_TOOL", "list_tasks", "v1", "agent-main"));
        coordinator.setDecideRouter(router);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        verify(repository).recordModelTurnWithSettlement(
                eq(run), eq(pythonTurn), any(), eq("MODEL_TURN"), any(), any(), any());
    }

    @Test
    void writeToolProposalKeepsModelCompletionMetadata() {
        AgentRunView run = runWithScenarioCode("ITERATION_PLANNING");
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
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(turn);
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
        AgentRunView run = runWithScenarioCode("ITERATION_PLANNING");
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
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(turn);
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
        AgentRunView run = runWithScenarioCode("ITERATION_PLANNING");
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
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(textResult("已处理"));

        coordinator.advance(run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), messages.capture(), any(), eq(false), any());
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
    void invalidTrustedContextEndsRunWithoutRepeatingWorkerClaims() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any()))
                .thenThrow(new BusinessException(ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID));

        assertThat(coordinator.advance(run).status()).isEqualTo(AgentRunStatus.FAILED);
        verify(repository).recordFailure(run,ErrorCode.AGENT_CONTEXT_RESOURCE_INVALID.name(),false);
    }

    @Test
    void normalRequestDoesNotEnterOldWorkerLoop() {
        // 验证 Coordinator 是独立处理，不依赖 AgentWorker 的旧循环
        AgentRunView run = run();
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));

        ModelTurnResult turn = textResult("完成");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        // 验证走的是 Coordinator 路径（有 recordModelTurn）
        verify(repository).recordModelTurnWithSettlement(eq(run), eq(turn), any(), eq("MODEL_TURN"), any(), any(), any());
        // 不应该有 requeue（除非有工具调用）
        verify(repository, never()).requeueRun(any());
    }

    @Test
    void promptKeepsRootListingAtRequestedDepth() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("读取仓库根目录", List.of()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenReturn(textResult("完成"));

        coordinator.advance(run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), messages.capture(), any(), eq(false), any());
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
        when(modelExecutor.callModel(eq(run), any(), anyList(), eq(false), any()))
                .thenReturn(textResult("根目录包含 docs、scripts 等。"));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> definitions = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), messages.capture(), definitions.capture(), eq(false), any());
        assertThat(definitions.getValue()).isEmpty();
        assertThat(messages.getValue())
                .filteredOn(ModelMessage.System.class::isInstance)
                .map(ModelMessage.System.class::cast)
                .extracting(ModelMessage.System::content)
                .anyMatch(content -> content.contains("只能基于已经取得的工具结果"));
        verify(repository, never()).requeueRun(any());
    }

    @Test
    void whenModelTruncatesAfterSuccessfulToolCalls_fallsBackToCompleteFromEvidence() {
        AgentRunView run = runAtUsage(5, 2);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.answer()).contains("因模型输出截断，先返回已取得的结果：");
        assertThat(outcome.answer()).contains("mcp.github-readonly.get_file_contents");
        verify(repository).recordBudgetPartialAnswer(eq(run), anyString(), eq(false),
                eq("AI_PROVIDER_OUTPUT_TRUNCATED"), eq("AI_PROVIDER_OUTPUT_TRUNCATED"), eq("PERSISTED_TOOL_EVIDENCE"));
    }

    @Test
    void whenCredentialInvalidAfterSuccessfulToolCalls_doesNotFallbackToBudgetExceeded_failsNormally() {
        AgentRunView run = runAtUsage(5, 2);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.AI_MODEL_CREDENTIAL_INVALID.name());
        verify(repository, never()).recordBudgetPartialAnswer(any(), any(), anyBoolean(), any(), any(), any());
        verify(repository).recordFailure(eq(run), eq(ErrorCode.AI_MODEL_CREDENTIAL_INVALID.name()), eq(false));
    }

    @Test
    void whenQuotaExceededAfterSuccessfulToolCalls_doesNotFallbackToBudgetExceeded_failsNormally() {
        AgentRunView run = runAtUsage(5, 2);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED.name());
        verify(repository, never()).recordBudgetPartialAnswer(any(), any(), anyBoolean(), any(), any(), any());
        verify(repository).recordFailure(eq(run), eq(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED.name()), eq(false));
    }

    @Test
    void whenProviderUnavailableAfterSuccessfulToolCalls_doesNotFallback_failsNormally() {
        AgentRunView run = runAtUsage(5, 2);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE.name());
        verify(repository, never()).recordBudgetPartialAnswer(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void whenOutputTruncatedWithoutToolEvidence_failsAsTruncatedWithoutFallback() {
        AgentRunView run = runAtUsage(5, 2);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of()); // 无工具证据
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED.name());
        verify(repository, never()).recordBudgetPartialAnswer(any(), any(), anyBoolean(), any(), any(), any());
        verify(repository).recordFailure(eq(run), eq(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED.name()), eq(false));
    }

    @Test
    void whenFormatRepairExhaustedWithToolEvidence_fallsBackToBudgetExceeded() {
        AgentRunView run = runAtUsage(5, 2);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(repository.consumeRecovery(eq(run), eq("FORMAT_REPAIR"), eq(1))).thenReturn(false);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.answer()).contains("因模型响应格式异常且重试耗尽，先返回已取得的结果：");
        verify(repository).recordBudgetPartialAnswer(eq(run), anyString(), eq(false),
                eq("AI_PROVIDER_INVALID_RESPONSE"), eq("FORMAT_REPAIR_EXHAUSTED"), eq("PERSISTED_TOOL_EVIDENCE"));
    }

    @Test
    void whenUnapprovedWriteOperationPending_doesNotTreatAsSuccessfulEvidence() {
        AgentRunView run = runAtUsage(5, 2);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        // 只有未批准的写操作提案，没有成功的只读工具
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(pendingWriteProposalStep()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED.name());
        verify(repository, never()).recordBudgetPartialAnswer(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void whenOnlyPendingWriteProposalAsToolSuccessPresent_doesNotTreatAsSummarizableEvidence_failsNormally() {
        AgentRunView run = runAtUsage(5, 2);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        // 关键反向场景：写提案被记录为 TOOL_CALL_COMPLETED、reason=TOOL_SUCCESS，但带有 effect=PROPOSAL_PENDING
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(pendingWriteProposalAsToolSuccessStep()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED.name());
        verify(repository, never()).recordBudgetPartialAnswer(any(), any(), anyBoolean(), any(), any(), any());
        verify(repository).recordFailure(eq(run), eq(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED.name()), eq(false));
    }

    @Test
    void whenBothReadOnlyToolAndPendingWriteProposalPresent_fallsBackWithoutIncludingPendingProposalInAnswer() {
        AgentRunView run = runAtUsage(5, 2);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        // 同时存在已完成只读工具与待审批写提案
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep(), pendingWriteProposalAsToolSuccessStep()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.answer()).contains("mcp.github-readonly.get_file_contents");
        assertThat(outcome.answer()).doesNotContain("task.create");
        assertThat(outcome.answer()).doesNotContain("PROPOSAL_PENDING");
        verify(repository).recordBudgetPartialAnswer(eq(run), anyString(), eq(false),
                eq("AI_PROVIDER_OUTPUT_TRUNCATED"), eq("AI_PROVIDER_OUTPUT_TRUNCATED"), eq("PERSISTED_TOOL_EVIDENCE"));
    }

    @Test
    void atomicEventRecorder_persistsBudgetExceededWithOriginalErrorCodeAndSingleEvent() {
        org.springframework.jdbc.core.JdbcTemplate jdbcMock = mock(
                org.springframework.jdbc.core.JdbcTemplate.class,
                invocation -> {
                    String name = invocation.getMethod().getName();
                    if (name.equals("update")) return 1;
                    if (name.equals("queryForList")) return List.of();
                    return null;
                });
        AgentEventService eventsMock = mock(AgentEventService.class);
        AgentRunEventRecorder recorder = new AgentRunEventRecorder(jdbcMock, json);
        recorder.setEvents(eventsMock);
        assertThat(recorder.atomicEventsEnabled()).isTrue();

        AgentRunView run = runAtUsage(5, 2);

        recorder.recordBudgetPartialAnswer(run, "部分回答内容", false,
                "AI_PROVIDER_INVALID_RESPONSE", "FORMAT_REPAIR_EXHAUSTED", "PERSISTED_TOOL_EVIDENCE");

        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(eventsMock, times(1)).append(
                eq(run.projectId()), eq(run.id()), eq(AgentEventType.RUN_BUDGET_EXCEEDED), payloadCaptor.capture());

        JsonNode payload = payloadCaptor.getValue();
        assertThat(payload.path("status").asText()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(payload.path("errorCode").asText()).isEqualTo("AGENT_BUDGET_EXCEEDED");
        assertThat(payload.path("originalErrorCode").asText()).isEqualTo("AI_PROVIDER_INVALID_RESPONSE");
        assertThat(payload.path("reason").asText()).isEqualTo("FORMAT_REPAIR_EXHAUSTED");
        assertThat(payload.path("fallback").asText()).isEqualTo("PERSISTED_TOOL_EVIDENCE");
    }

    @Test
    void coordinatorUnderAtomicEvents_delegatesToRepositoryAndDoesNotEmitDuplicateEvent() {
        AgentRunView run = runAtUsage(5, 2);
        AgentEventService eventsMock = mock(AgentEventService.class);
        when(repository.atomicEventsEnabled()).thenReturn(true);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("检查项目", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED));

        AgentRuntimeCoordinator coordWithEvents = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService,
                tools, cancellation, loopGuard, approvals, modelExecutor, sanitizer,
                new AgentConvergencePolicy(), json, eventsMock, mock(AgentMemoryService.class));

        AgentWorkerOutcome outcome = coordWithEvents.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        // 验证元数据完整传给持久化记录器
        verify(repository).recordBudgetPartialAnswer(eq(run), anyString(), eq(false),
                eq("AI_PROVIDER_OUTPUT_TRUNCATED"), eq("AI_PROVIDER_OUTPUT_TRUNCATED"), eq("PERSISTED_TOOL_EVIDENCE"));
        // 协调器禁止重复发出第二个终态事件
        verify(eventsMock, never()).append(any(), any(), eq(AgentEventType.RUN_BUDGET_EXCEEDED), any());
    }

    /**
     * 预算强制收尾且核心动作（start_task_plan）未发生：运行进入 BUDGET_EXCEEDED
     * 部分完成状态，不得仅凭模型返回文字记 SUCCEEDED（真实第 23 轮回归）。
     */
    @Test
    void budgetFinalizeWithoutCoreActionIsNotRecordedAsSuccess() {
        AgentRunView run = planningRunAtUsage(14, 5);
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("规划", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(repository.hasSuccessfulToolInvocation(eq(run.id()), any())).thenReturn(false);
        when(modelExecutor.callModel(eq(run), any(), anyList(), eq(false), any()))
                .thenReturn(textResult("本轮未调用 start_task_plan，规划草稿尚未生成。"));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(repository).recordBudgetPartialAnswer(eq(run), contains("start_task_plan"), eq(true));
        verify(repository, never()).recordFinal(any(), any(), anyList(), anyBoolean());
    }

    /** 核心动作已有成功受理（持久工具结果）：预算收尾下的最终回答正常记成功。 */
    @Test
    void budgetFinalizeWithCoreActionPerformedStillSucceeds() {
        AgentRunView run = planningRunAtUsage(14, 5);
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("规划", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(repository.hasSuccessfulToolInvocation(eq(run.id()), any())).thenReturn(true);
        when(modelExecutor.callModel(eq(run), any(), anyList(), eq(false), any()))
                .thenReturn(textResult("规划草稿已受理，等待人工确认。"));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        verify(repository).recordFinal(eq(run), anyString(), anyList(), eq(true));
        verify(repository, never()).recordBudgetPartialAnswer(any(), anyString(), anyBoolean());
    }

    /** 非收尾轮次不因核心动作提示改变完成判定；提示只注入给未完成核心动作的运行。 */
    @Test
    void coreActionHintIsInjectedBeforeFinalizeWithoutForcingWriteTools() {
        AgentRunView run = planningRunAtUsage(2, 0);
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("规划", List.of()));
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(List.of());
        when(repository.hasSuccessfulToolInvocation(eq(run.id()), any())).thenReturn(false);
        when(modelExecutor.callModel(eq(run), any(), anyList(), eq(false), any()))
                .thenReturn(textResult("先核对事实。"));

        coordinator.advance(run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), messages.capture(), any(), eq(false), any());
        assertThat(messages.getValue())
                .filteredOn(ModelMessage.System.class::isInstance)
                .map(ModelMessage.System.class::cast)
                .extracting(ModelMessage.System::content)
                .anyMatch(content -> content.contains("立即调用 start_task_plan"))
                .noneMatch(content -> content.contains("只能基于已经取得的工具结果"));
    }

    @Test
    void finalBoundaryFallsBackToPersistedEvidenceWhenModelCallsAnotherTool() {        AgentRunView run = runAtUsage(14, 5);
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("列出根目录", List.of()));
        when(repository.listSteps(run.projectId(), run.id()))
                .thenReturn(List.of(successfulToolStep()));
        when(modelExecutor.callModel(eq(run), any(), anyList(), eq(false), any()))
                .thenReturn(toolCallResult(new ModelToolCall(
                        "call-extra", "list_tasks", json.createObjectNode())));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.answer()).contains("已取得的结果").contains("mcp.github-readonly.get_file_contents");
        verify(repository).recordBudgetPartialAnswer(any(), contains("已取得的结果"), anyBoolean(), any(), any(), any());
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

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.answer()).contains("已取得的结果").contains("type");
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        verify(repository, never()).recordBudgetExceeded(any());
    }

    @Test
    void rejectsToolBatchAbovePerTurnBudgetBeforeExecution() {
        AgentRunView run = runWithScenarioCode("ITERATION_PLANNING");
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any()))
                .thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("规划", List.of()));
        ModelToolCall[] calls = new ModelToolCall[5];
        for (int index = 0; index < calls.length; index++) {
            calls[index] = new ModelToolCall(
                    "call-" + index, "list_tasks", json.createObjectNode());
        }
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenReturn(toolCallResult(calls));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(repository).recordBudgetExceeded(any());
        verify(repository, never()).recordToolResult(any(), any(), any(), any(), anyBoolean());
        verify(repository, never()).requeueRun(any());
    }

    // ========== 辅助方法 ==========

    /**
     * 摘要入账后必须刷新运行并重新核算主请求预算：预算被摘要耗尽时明确停止，
     * 不得携带超限上下文继续请求主模型。
     */
    @Test
    void summaryAccountingTriggersBudgetRecheckBeforeMainModelCall() {
        AgentRunView run = run();
        AgentExecutionContext tightLimits = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", false, AgentPageContext.empty(),
                new AgentRuntimeLimits(8, 4, 6, 2, java.time.Duration.ofMinutes(2),
                        java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(15),
                        32 * 1024, 6_000, 2_000),
                0, List.of());
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(tightLimits);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());

        // v2 工作状态 + 最近几条超预算的大消息，使其余小消息成为摘要候选
        ObjectNode state = json.createObjectNode();
        state.put("schemaVersion", 2);
        state.put("stateRevision", 4);
        state.put("goalRevision", 1);
        state.put("activeGoal", "检查项目");
        when(repository.workingState(any(), any())).thenReturn(state);
        java.util.List<com.shitulelv.aicollab.agent.application.view.AgentMessageView> history = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(new com.shitulelv.aicollab.agent.application.view.AgentMessageView(
                    UUID.randomUUID(), run.sessionId(), run.id(), "USER",
                    ("大消息 %d：".formatted(i)) + "项目内容。".repeat(600), null, null,
                    OffsetDateTime.now().plusSeconds(i)));
        }
        for (int i = 0; i < 3; i++) {
            history.add(new com.shitulelv.aicollab.agent.application.view.AgentMessageView(
                    UUID.randomUUID(), run.sessionId(), run.id(), "ASSISTANT",
                    "小消息 " + i + "：任务状态正常，无阻塞。", null, null,
                    OffsetDateTime.now().plusSeconds(10 + i)));
        }
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);
        when(repository.countSummaryAttempts(any(), any())).thenReturn(0);
        when(repository.beginSummaryAttempt(any())).thenReturn(UUID.randomUUID());
        when(repository.commitConversationSummary(any(), any(), anyInt(), anyInt(), any())).thenReturn(true);
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(new ModelTurnResult(
                "摘要内容", List.of(), ModelFinishReason.STOP,
                new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(500, 60),
                "test-provider", "test-model", 5L));

        // 摘要入账后刷新出的 run：输入预算已耗尽
        OffsetDateTime now = OffsetDateTime.now();
        AgentRunView refreshed = new AgentRunView(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 6_000, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 2, now, now);
        when(repository.findRun(any(), any()))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.of(refreshed));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        // 主模型调用不得发生：预算已被摘要消耗并重新核算为不足
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 返回后输入实际超额：结算真实消耗并明确终止，不执行该响应中的工具，不记成功。
     * 回归根因：fixed24-final2 第 14 轮真实输入 52,289 > 50,000 仍 SUCCEEDED。
     */
    @Test
    void actualInputOvershootAfterModelTurnEndsRunWithoutExecutingTools() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(
                new ModelTurnResult("继续分析", List.of(), ModelFinishReason.STOP,
                        new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(200_000, 500),
                        "test-provider", "test-model", 10L));
        // recordModelTurn 结算真实消耗后返回的运行：actual 已超过 max
        OffsetDateTime now = OffsetDateTime.now();
        AgentRunView settled = new AgentRunView(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                1, 0, 0, 100_000, 500, 200_000, 500, false,
                false, false, 0, null, null, null, null, 2, now, now);
        when(repository.recordModelTurnWithSettlement(eq(run), any(), any(), any(), any(), any(), any())).thenReturn(settled);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(repository).recordModelTurnWithSettlement(eq(run), any(), any(), eq("MODEL_TURN"), any(), any(), any());
        verify(repository).recordBudgetExceeded(any());
        verify(repository, never()).recordFinal(any(), any(),
                any(com.shitulelv.aicollab.agent.domain.model.AgentDecision.FinalAnswer.class));
        verify(repository, never()).recordFinal(any(), any(), anyList(), anyBoolean());
        verify(repository, never()).recordToolResult(any(), any(), any(), any(), anyBoolean());
    }

    /**
     * 最后一次取消检查之后、落库之前收到取消：recordModelTurn 的租约校验抛出取消，
     * 已返回响应的用量必须按调用身份补结算，不得随取消丢失。
     */
    /** 异常携带完整提供商用量：结算保留真实值（PROVIDER），不替换成估算。 */
    @Test
    void providerFailureCarriesFullUsageIntoSettlement() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new com.shitulelv.aicollab.infrastructure.ai.model.ProviderResponseFailure(
                        ErrorCode.AI_MODEL_TIMEOUT, "provider timeout", 1234, 567));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED_RETRYABLE);
        ArgumentCaptor<AgentRunEventRecorder.UsageSettlement> settlement =
                ArgumentCaptor.forClass(AgentRunEventRecorder.UsageSettlement.class);
        verify(repository).settleOrphanUsage(eq(run.projectId()), eq(run.id()), any(), eq("MODEL_TURN"), settlement.capture());
        assertThat(settlement.getValue().inputTokens()).isEqualTo(1234);
        assertThat(settlement.getValue().outputTokens()).isEqualTo(567);
        assertThat(settlement.getValue().combinedBasis()).isEqualTo("PROVIDER");
    }

    /** Legacy 解析失败（IllegalArgumentException 携带用量）到最终结算：真实值保留。 */
    @Test
    void legacyParseFailureCarriesCompletionUsageIntoSettlement() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());
        when(repository.consumeRecovery(any(), eq("FORMAT_REPAIR"), eq(1))).thenReturn(false);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new LegacyDecisionParseFailure("Agent 决策不是合法 JSON", 100, 50));

        coordinator.advance(run);

        ArgumentCaptor<AgentRunEventRecorder.UsageSettlement> settlement =
                ArgumentCaptor.forClass(AgentRunEventRecorder.UsageSettlement.class);
        verify(repository).settleOrphanUsage(eq(run.projectId()), eq(run.id()), any(), eq("MODEL_TURN"), settlement.capture());
        assertThat(settlement.getValue().inputTokens()).isEqualTo(100);
        assertThat(settlement.getValue().outputTokens()).isEqualTo(50);
        assertThat(settlement.getValue().combinedBasis()).isEqualTo("PROVIDER");
    }

    /** 异常仅携带单侧用量：提供商侧保留，缺失侧按证据估算，混合来源显式表示。 */
    @Test
    void providerFailureWithSingleSideUsageKeepsProviderSide() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenThrow(new com.shitulelv.aicollab.infrastructure.ai.model.ProviderResponseFailure(
                        ErrorCode.AI_MODEL_TIMEOUT, "provider timeout", 1234, null));

        coordinator.advance(run);

        ArgumentCaptor<AgentRunEventRecorder.UsageSettlement> settlement =
                ArgumentCaptor.forClass(AgentRunEventRecorder.UsageSettlement.class);
        verify(repository).settleOrphanUsage(eq(run.projectId()), eq(run.id()), any(), eq("MODEL_TURN"), settlement.capture());
        assertThat(settlement.getValue().inputTokens()).isEqualTo(1234);
        assertThat(settlement.getValue().inputBasis()).isEqualTo("PROVIDER");
        assertThat(settlement.getValue().outputBasis()).isEqualTo("UNKNOWN");
        assertThat(settlement.getValue().combinedBasis()).isEqualTo("ESTIMATED");
    }

    @Test
    void cancelAfterLastCheckBeforeBookingStillSettlesReturnedUsage() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(textResult("已取得的结论"));
        when(repository.recordModelTurnWithSettlement(eq(run), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AGENT_RUN_CANCELED));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.CANCELED);
        verify(repository).settleOrphanUsage(eq(run.projectId()), eq(run.id()), any(), eq("MODEL_TURN"), any());
        verify(repository).recordCanceled(run);
    }

    /** 落库本身失败（租约过期/版本冲突）：已返回响应的消耗按调用身份结算，不丢账后原样抛出。 */
    @Test
    void bookingFailureStillSettlesReturnedUsage() {
        AgentRunView run = run();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(textResult("已取得的结论"));
        when(repository.recordModelTurnWithSettlement(eq(run), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Agent worker lease has expired"));

        assertThatThrownBy(() -> coordinator.advance(run)).isInstanceOf(IllegalStateException.class);
        verify(repository).settleOrphanUsage(eq(run.projectId()), eq(run.id()), any(), eq("MODEL_TURN"), any());
    }

    /** 核心动作意图：肯定与否定覆盖相同动词；疑问/假设保守；分句后以后一子句为准。 */
    @Test
    void coreActionIntentHonorsMirroredNegativesQuestionsAndClauses() {
        // 明确否定（同动词覆盖：生成/起草/制定/启动）
        assertThat(AgentRuntimeCoordinator.coreActionRequested("不要起草规划，只解释流程")).isFalse();
        assertThat(AgentRuntimeCoordinator.coreActionRequested("无需制定计划，先说明现状")).isFalse();
        assertThat(AgentRuntimeCoordinator.coreActionRequested("先别启动规划生成，先解释边界")).isFalse();
        // 疑问/假设保守
        assertThat(AgentRuntimeCoordinator.coreActionRequested("是否需要生成规划？")).isFalse();
        assertThat(AgentRuntimeCoordinator.coreActionRequested("要不要起草规划呢")).isFalse();
        assertThat(AgentRuntimeCoordinator.coreActionRequested("如果起草规划会怎样")).isFalse();
        // 分句：早子句否定不覆盖晚子句请求；晚子句否定也不覆盖早子句请求
        assertThat(AgentRuntimeCoordinator.coreActionRequested("先不生成；现在生成完整规划草稿")).isTrue();
        assertThat(AgentRuntimeCoordinator.coreActionRequested("现在生成草稿；之后不要起草规划")).isTrue();
        // 原场景措辞
        assertThat(AgentRuntimeCoordinator.coreActionRequested(
                "把上限调整为最多8项；这取代原来最多10项。仍不生成规划。请复述当前有效约束。")).isFalse();
        assertThat(AgentRuntimeCoordinator.coreActionRequested(
                "现在生成同一可靠性目标的完整规划草稿，标题为ai-collab可靠性验收24轮")).isTrue();
    }

    /** 摘要耗尽输出预留：主调用不再发生，明确 BUDGET_EXCEEDED（scope=SUMMARY_CONSUMED_OUTPUT_BUDGET）。 */
    @Test
    void summaryConsumingOutputBudgetStopsBeforeMainModelCall() {
        AgentRunView run = run();
        AgentExecutionContext tightLimits = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", false, AgentPageContext.empty(),
                new AgentRuntimeLimits(8, 4, 6, 2, java.time.Duration.ofMinutes(2),
                        java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(15),
                        32 * 1024, 32 * 1024, 1_000),
                0, List.of());
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(tightLimits);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());
        ObjectNode state = json.createObjectNode();
        state.put("schemaVersion", 2);
        state.put("stateRevision", 4);
        state.put("goalRevision", 1);
        state.put("activeGoal", "检查项目");
        when(repository.workingState(any(), any())).thenReturn(state);
        java.util.List<com.shitulelv.aicollab.agent.application.view.AgentMessageView> history = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(new com.shitulelv.aicollab.agent.application.view.AgentMessageView(
                    UUID.randomUUID(), run.sessionId(), run.id(), "USER",
                    ("大消息 %d：".formatted(i)) + "项目内容。".repeat(600), null, null,
                    OffsetDateTime.now().plusSeconds(i)));
        }
        for (int i = 0; i < 3; i++) {
            history.add(new com.shitulelv.aicollab.agent.application.view.AgentMessageView(
                    UUID.randomUUID(), run.sessionId(), run.id(), "ASSISTANT",
                    "小消息 " + i + "：任务状态正常，无阻塞。", null, null,
                    OffsetDateTime.now().plusSeconds(10 + i)));
        }
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);
        when(repository.countSummaryAttempts(any(), any())).thenReturn(0);
        when(repository.beginSummaryAttempt(any())).thenReturn(UUID.randomUUID());
        when(repository.commitConversationSummary(any(), any(), anyInt(), anyInt(), any())).thenReturn(true);
        when(modelExecutor.callModelWithoutTools(any(), any())).thenReturn(new ModelTurnResult(
                "摘要内容", List.of(), ModelFinishReason.STOP,
                new com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage(500, 900),
                "test-provider", "test-model", 5L));

        // 摘要入账后刷新出的 run：输出预算已接近耗尽（上限 32_000，actual 已 31_500）
        OffsetDateTime now = OffsetDateTime.now();
        AgentRunView refreshed = new AgentRunView(
                run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, 0, 31_500, false,
                false, false, 0, null, null, null, null, 2, now, now);
        when(repository.findRun(any(), any()))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.of(refreshed));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        verify(repository).recordBudgetExceeded(any());
    }

    // ========== 输入预算软阈值分级收敛（预算治理 v3 P0） ==========

    /** 在 v1 兼容形状上重建 run，仅替换真实累计输入列（input_tokens_actual）。 */
    private AgentRunView withInputTokensActual(AgentRunView r, long actual) {
        return new AgentRunView(
                r.id(), r.sessionId(), r.projectId(), r.requesterId(),
                r.parentRunId(), r.role(), r.depth(), r.goal(), r.status(),
                r.maxSteps(), r.maxToolCalls(), r.maxChildren(),
                r.maxInputTokens(), r.maxOutputTokens(),
                r.stepsUsed(), r.toolCallsUsed(), r.childrenUsed(),
                r.inputTokensUsed(), r.outputTokensUsed(), actual, r.outputTokensActual(),
                r.tokenUsageEstimated(), r.scheduled(), r.correctionAttempted(),
                r.retryCount(), r.errorCode(), r.planJson(), r.pageContextJson(),
                r.skillCode(), r.version(), r.createdAt(), r.updatedAt());
    }

    private void stubPlainAnswerTurn(AgentRunView run) {
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(context());
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any()))
                .thenReturn(textResult("回答"));
    }

    private boolean hasSystemMessageContaining(List<ModelMessage> messages, String fragment) {
        return messages.stream()
                .filter(message -> message instanceof ModelMessage.System)
                .map(message -> ((ModelMessage.System) message).content())
                .anyMatch(content -> content.contains(fragment));
    }

    @Test
    void injectsConvergeHintWhenActualInputReachesSoftThreshold() {
        // 默认软顶 150000：120000 = 80% → CONVERGE_HINT
        AgentRunView run = withInputTokensActual(run(), 120_000);
        stubPlainAnswerTurn(run);

        coordinator.advance(run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), captor.capture(), any(), eq(false), any());
        assertThat(hasSystemMessageContaining(captor.getValue(), "软顶")).isTrue();
        assertThat(hasSystemMessageContaining(captor.getValue(), "收敛已有信息作答")).isTrue();
    }

    @Test
    void injectsRestrictHintWhenActualInputReachesEightyFivePercent() {
        // 140000 / 150000 ≈ 93% → RESTRICT
        AgentRunView run = withInputTokensActual(run(), 140_000);
        stubPlainAnswerTurn(run);

        coordinator.advance(run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), captor.capture(), any(), eq(false), any());
        assertThat(hasSystemMessageContaining(captor.getValue(), "85%")).isTrue();
    }

    @Test
    void injectsNoBudgetHintBelowSoftThreshold() {
        AgentRunView run = run(); // 0 累计输入
        stubPlainAnswerTurn(run);

        coordinator.advance(run);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor).callModel(eq(run), captor.capture(), any(), eq(false), any());
        // "软顶"只出现在软阈值提示文本中，其余既有提示不包含该词
        assertThat(hasSystemMessageContaining(captor.getValue(), "软顶")).isFalse();
    }

    @Test
    void emitsSoftBudgetWarningMetricPerModelRound() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        AgentRuntimeCoordinator observed = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService, tools, cancellation,
                modelExecutor, new AgentConvergencePolicy(), json, events,
                AgentContextProperties.defaults(), AgentBudgetProperties.defaults(),
                new com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentRuntimeMetrics(registry),
                new AgentModelMessageComposer(repository, null, json),
                new AgentToolCallExecutor(repository, tools, cancellation, loopGuard, approvals,
                        modelExecutor, sanitizer, json, events, new AgentToolScheduler()),
                new AgentContextSummarizer(repository, modelExecutor, json));
        // 140000 / 150000 ≈ 93% → RESTRICT
        AgentRunView run = withInputTokensActual(run(), 140_000);
        stubPlainAnswerTurn(run);

        observed.advance(run);

        assertThat(registry.get("agent.budget.soft.warning")
                .tag("level", "RESTRICT").counter().count()).isEqualTo(1.0);
        // 下一模型轮重算同级别：再次计数（每轮至多一次，天然幂等）
        observed.advance(run);
        assertThat(registry.get("agent.budget.soft.warning")
                .tag("level", "RESTRICT").counter().count()).isEqualTo(2.0);
    }

    @Test
    void countsUnknownWindowMetricWhenResolverReturnsUnknown() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ModelWindowResolver resolver = mock(ModelWindowResolver.class);
        when(resolver.resolve(any(), any(), any(), any()))
                .thenReturn(AgentContextBudget.ModelWindow.unknown());
        AgentRuntimeCoordinator observed = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService, tools, cancellation,
                modelExecutor, new AgentConvergencePolicy(), json, null,
                AgentContextProperties.defaults(), AgentBudgetProperties.defaults(),
                new com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentRuntimeMetrics(registry),
                new AgentModelMessageComposer(repository, null, json),
                new AgentToolCallExecutor(repository, tools, cancellation, loopGuard, approvals,
                        modelExecutor, sanitizer, json, null, new AgentToolScheduler()),
                new AgentContextSummarizer(repository, modelExecutor, json),
                resolver);
        AgentRunView run = run();
        stubPlainAnswerTurn(run);

        observed.advance(run);

        assertThat(registry.get("agent.context.window.unknown")
                .tags("provider", "OPENAI_COMPATIBLE", "model", "test-native")
                .counter().count()).isEqualTo(1.0);
        verify(resolver).resolve(any(), any(), any(), any());
    }

    private AgentRunView run() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
    }

    // ========== 上下文预算降级重组回归 ==========

    private AgentEventService events;

    /** 必选层体积（系统提示 + 页面上下文；无历史、无工具观察、无工作状态）。 */
    private int mandatoryLayerChars(AgentExecutionContext ctx, AgentScenario skill) {
        AgentModelMessageComposer probe = new AgentModelMessageComposer(repository, null, json);
        AgentRunView probeRun = run();
        var baseline = probe.composeV2(probeRun, ctx, skill, plan("规划", List.of()), List.of(), 1_000_000, 1.0, false);
        assertThat(baseline.failureReason()).isNull();
        return baseline.stats().charsUsed() - probeRun.goal().length();
    }

    /** 注册 list_tasks（ITERATION_PLANNING 允许）并让工具定义体积达到约 descriptionRepeat×5 字符。 */
    private AgentRuntimeCoordinator coordinatorWithLargeToolDefinition(int descriptionRepeat) {
        AgentTool bigTool = new AgentTool() {
            @Override public String name() { return "list_tasks"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolDefinition definition() {
                return AgentToolDefinition.openObject("list_tasks", "工具说明。".repeat(descriptionRepeat), false);
            }
            @Override public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
                throw new AssertionError("工具不应被调用");
            }
        };
        var registry = new AgentToolRegistry(List.of(bigTool));
        return new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService, registry, cancellation,
                modelExecutor, new AgentConvergencePolicy(), json, events,
                new AgentContextProperties(4_000, 8_000, 2_000, java.util.Map.of()),
                new AgentModelMessageComposer(repository, null, json),
                new AgentToolCallExecutor(repository, registry, cancellation, loopGuard, approvals,
                        modelExecutor, sanitizer, json, events, new AgentToolScheduler()),
                new AgentContextSummarizer(repository, modelExecutor, json));
    }

    private AgentRunView withGoal(AgentRunView r, String goal) {
        return new AgentRunView(
                r.id(), r.sessionId(), r.projectId(), r.requesterId(),
                r.parentRunId(), r.role(), r.depth(), goal, r.status(),
                r.maxSteps(), r.maxToolCalls(), r.maxChildren(),
                r.maxInputTokens(), r.maxOutputTokens(),
                r.stepsUsed(), r.toolCallsUsed(), r.childrenUsed(),
                r.inputTokensUsed(), r.outputTokensUsed(), r.inputTokensActual(), r.outputTokensActual(),
                r.tokenUsageEstimated(), r.scheduled(), r.correctionAttempted(),
                r.retryCount(), r.errorCode(), r.planJson(), r.pageContextJson(), r.skillCode(),
                r.version(), r.createdAt(), r.updatedAt());
    }

    /**
     * 第一次组装成功（必选层 + 当前请求 ≤ 预算），计入工具定义开销后估算超预算，进入降级重组；
     * 降级后（0.6 系数）必选层溢出：必须明确进入预算终态并记录原因，
     * 不得以丢失当前目标与有效约束的空上下文继续调用模型。
     */
    @Test
    void degradeRecompositionWithOverflowingMandatoryLayerEndsRunWithoutModelCall() {
        AgentExecutionContext ctx = context();
        AgentScenario skill = skillRegistry.select("ITERATION_PLANNING", ctx.page());
        int mandatoryChars = mandatoryLayerChars(ctx, skill);
        events = mock(AgentEventService.class);
        coordinator = coordinatorWithLargeToolDefinition(500);

        // J0-1 统一 token 预算：goalChars = 11000 - mandatoryChars，目标为 CJK（0.75 token/字），
        // 必选层 token 已超过 4000 的单次预算 → 明确 CURRENT_REQUEST_OVER_BUDGET，不调用模型。
        int goalChars = 11000 - mandatoryChars;
        assertThat(goalChars).isPositive();
        AgentRunView run = withGoal(runWithScenarioCode("ITERATION_PLANNING"), "目".repeat(goalChars));
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("规划", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_BUDGET_EXCEEDED");
        verify(modelExecutor, never()).callModel(any(), any(), any(), anyBoolean(), any());
        verify(repository).recordBudgetExceeded(run);
        verify(events).append(eq(run.projectId()), eq(run.id()), eq(AgentEventType.RUN_BUDGET_EXCEEDED),
                argThat(payload -> "PER_REQUEST_INPUT".equals(payload.path("scope").asText())
                        && "CURRENT_REQUEST_OVER_BUDGET".equals(payload.path("reason").asText())));
    }

    /**
     * 正常降级成功保留：第一次组装装满预算、计入工具定义后估算超预算，降级重组
     * （0.6 系数）下可选层收缩、估算回到预算内，当前请求完整保留并继续调用模型完成。
     */
    @Test
    void degradeRecompositionSuccessStillCallsModelWithCurrentRequest() {
        AgentExecutionContext ctx = context();
        AgentScenario skill = skillRegistry.select("ITERATION_PLANNING", ctx.page());
        int mandatoryChars = mandatoryLayerChars(ctx, skill);
        events = mock(AgentEventService.class);
        // 本用例自带标定的协调器：单次上限 10000 + 大工具定义（700×5 字 CJK ≈ 2625 token），
        // 不复用 coordinatorWithLargeToolDefinition（其 4000 上限的算术被降级用例占用）
        var registry = new AgentToolRegistry(List.of(new AgentTool() {
            @Override public String name() { return "list_tasks"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolDefinition definition() {
                return AgentToolDefinition.openObject("list_tasks", "工具说明。".repeat(700), false);
            }
            @Override public AgentToolResult execute(AgentToolContext context, JsonNode arguments) {
                throw new AssertionError("工具不应被调用");
            }
        }));
        coordinator = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService, registry, cancellation,
                modelExecutor, new AgentConvergencePolicy(), json, events,
                new AgentContextProperties(10_000, 8_000, 2_000, java.util.Map.of()),
                new AgentModelMessageComposer(repository, null, json),
                new AgentToolCallExecutor(repository, registry, cancellation, loopGuard, approvals,
                        modelExecutor, sanitizer, json, events, new AgentToolScheduler()),
                new AgentContextSummarizer(repository, modelExecutor, json));

        int goalChars = 500;
        AgentRunView run = withGoal(runWithScenarioCode("ITERATION_PLANNING"), "g".repeat(goalChars));
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("规划", List.of()));
        when(repository.listSteps(any(), any())).thenReturn(List.of());
        when(repository.pendingModelTurn(any())).thenReturn(java.util.Optional.empty());

        // 40 条（HISTORY_CANDIDATES 上限）唯一历史消息：约 30k ASCII 字符 ≈ 7.5k token。
        // 第一次组装 token 预算 10000，计入工具定义（700×5 字 CJK ≈ 2450 token）后
        // 估算超预算 → 降级；降级预算 0.6×10000=6000 token 下历史收缩，
        // 当前请求完整保留。旧 chars/3 口径下的标定已不适用。
        int historyBudget = 30_000 - (mandatoryChars + goalChars);
        int messageChars = historyBudget / 40 + 60;
        java.util.List<com.shitulelv.aicollab.agent.application.view.AgentMessageView> history = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String content = ("history-" + i + ":") + "x".repeat(Math.max(1, messageChars - 10));
            history.add(new com.shitulelv.aicollab.agent.application.view.AgentMessageView(
                    UUID.randomUUID(), run.sessionId(), run.id(), "USER", content, null, null,
                    OffsetDateTime.now().plusSeconds(i)));
        }
        when(repository.listRecentMessages(any(), anyInt())).thenReturn(history);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(textResult("完成"));

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).isEqualTo("完成");
        verify(repository, never()).recordBudgetExceeded(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelExecutor, times(1)).callModel(eq(run), messages.capture(), any(), eq(false), any());
        // 当前请求完整保留：降级后的请求消息仍包含完整目标
        assertThat(messages.getValue())
                .filteredOn(ModelMessage.User.class::isInstance)
                .map(ModelMessage.User.class::cast)
                .extracting(ModelMessage.User::content)
                .anyMatch(content -> content.equals("g".repeat(goalChars)));
    }


    @Test
    void toolResultIsSanitizedBeforeRecording() {
        // 使用 ITERATION_PLANNING skill，它允许 list_tasks
        AgentRunView run = runWithScenarioCode("ITERATION_PLANNING");
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
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(turn1);

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

    @Test void controlledPlanningToolEndsAtAcceptedAndPreservesInvocationIdentity() {
        var run=runWithScenarioCode("ITERATION_PLANNING");UUID invocation=UUID.randomUUID();
        var ctx=new AgentExecutionContext(run.id(),run.sessionId(),run.projectId(),run.requesterId(),"OWNER",false,AgentPageContext.empty(),AgentRuntimeLimits.defaults(),0,List.of());
        when(contextAssembler.assemble(eq(run),any(),any())).thenReturn(ctx);when(planService.ensurePlan(eq(run),any())).thenReturn(plan("规划",List.of()));
        when(repository.invocationId(eq(run),any())).thenReturn(invocation);
        var completed=new java.util.concurrent.atomic.AtomicReference<List<AgentStepView>>(List.of());
        when(repository.listSteps(any(),any())).thenAnswer(i->completed.get());
        when(repository.recordToolResult(any(),any(),any(),any(),anyBoolean())).thenAnswer(i->{completed.set(List.of(new AgentStepView(UUID.randomUUID(),1,AgentStepType.TOOL_CALL_COMPLETED,i.getArgument(1),i.getArgument(2),i.getArgument(3),"TOOL_SUCCESS",null,null,false,null,null,OffsetDateTime.now())));return i.getArgument(0);});
        var tool=new ControlledWriteAgentTool(){
            public String name(){return "start_task_plan";}
            public AgentToolResult execute(AgentToolContext context,JsonNode args){assertThat(context.invocationId()).isEqualTo(invocation);return new AgentToolResult(json.createObjectNode().put("operationId",UUID.randomUUID().toString()).put("status","SKELETON_GENERATING"),List.of(),List.of());}
        };
        var registry=new AgentToolRegistry(List.of(tool));coordinator=new AgentRuntimeCoordinator(repository,contextAssembler,skillRegistry,planService,registry,cancellation,loopGuard,approvals,modelExecutor,sanitizer,json);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false), any())).thenReturn(toolCallResult(new ModelToolCall("call-plan","start_task_plan",json.createObjectNode())));
        var outcome=coordinator.advance(run);assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).contains("已受理").doesNotContain("生成成功");
        verify(modelExecutor,times(1)).callModel(eq(run), any(), any(), eq(false), any());verify(repository,never()).requeueRun(any());verifyNoInteractions(approvals);
    }

    private AgentRunView runWithScenarioCode(String skillCode) {
        AgentRunView r = run();
        return new AgentRunView(
                r.id(), r.sessionId(), r.projectId(), r.requesterId(),
                r.parentRunId(), r.role(), r.depth(), r.goal(), r.status(),
                r.maxSteps(), r.maxToolCalls(), r.maxChildren(),
                r.maxInputTokens(), r.maxOutputTokens(),
                r.stepsUsed(), r.toolCallsUsed(), r.childrenUsed(),
                r.inputTokensUsed(), r.outputTokensUsed(), 0, 0,
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
                r.inputTokensUsed(), r.outputTokensUsed(), 0, 0,
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
                r.inputTokensUsed(), r.outputTokensUsed(), 0, 0,
                r.tokenUsageEstimated(), r.scheduled(), r.correctionAttempted(),
                r.retryCount(), r.errorCode(), r.planJson(), r.pageContextJson(), r.skillCode(),
                r.version(), r.createdAt(), r.updatedAt());
    }

    /** ITERATION_PLANNING 运行，目标明确要求生成规划草稿（真实第 23 轮措辞）。 */
    private AgentRunView planningRunAtUsage(int stepsUsed, int toolCallsUsed) {
        AgentRunView r = runAtUsage(stepsUsed, toolCallsUsed);
        return new AgentRunView(
                r.id(), r.sessionId(), r.projectId(), r.requesterId(),
                r.parentRunId(), r.role(), r.depth(),
                "现在生成同一可靠性目标的完整规划草稿，遵守当前有效约束，不确认正式任务。",
                r.status(),
                r.maxSteps(), r.maxToolCalls(), r.maxChildren(),
                r.maxInputTokens(), r.maxOutputTokens(),
                r.stepsUsed(), r.toolCallsUsed(), r.childrenUsed(),
                r.inputTokensUsed(), r.outputTokensUsed(), r.inputTokensActual(), r.outputTokensActual(),
                r.tokenUsageEstimated(), r.scheduled(), r.correctionAttempted(),
                r.retryCount(), r.errorCode(), r.planJson(), r.pageContextJson(), "ITERATION_PLANNING",
                r.version(), r.createdAt(), r.updatedAt());
    }

    private AgentStepView successfulToolStep() {
        return new AgentStepView(
                UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "mcp.github-readonly.get_file_contents", json.createObjectNode(),
                json.createObjectNode().put("type", "dir"), "TOOL_SUCCESS",
                null, null, false, null, null, OffsetDateTime.now());
    }

    private AgentStepView pendingWriteProposalStep() {
        return new AgentStepView(
                UUID.randomUUID(), 1, AgentStepType.APPROVAL_REQUESTED,
                "task.create", json.createObjectNode().put("title", "新任务"),
                null, "WAITING_APPROVAL",
                null, null, false, null, null, OffsetDateTime.now());
    }

    private AgentStepView pendingWriteProposalAsToolSuccessStep() {
        return new AgentStepView(
                UUID.randomUUID(), 1, AgentStepType.TOOL_CALL_COMPLETED,
                "task.create", json.createObjectNode().put("title", "新任务"),
                json.createObjectNode().put("status", "SUCCEEDED").put("effect", "PROPOSAL_PENDING").put("proposalId", UUID.randomUUID().toString()),
                "TOOL_SUCCESS",
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
