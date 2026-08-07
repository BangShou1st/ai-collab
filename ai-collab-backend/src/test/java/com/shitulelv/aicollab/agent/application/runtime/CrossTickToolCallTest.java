package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
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
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 跨 Tick Tool Call 测试。
 * 验证工具执行后 Run 进入 QUEUED，下一次 RuntimeJob 重新领取时能正确恢复历史。
 * <p>
 * 使用 ITERATION_PLANNING skill，它允许 list_tasks 和 get_task 工具。
 */
class CrossTickToolCallTest {
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

    private final List<AgentStepView> stepStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        contextAssembler = mock(AgentContextAssembler.class);
        skillRegistry = new AgentSkillRegistry();
        planService = mock(AgentPlanService.class);
        cancellation = new AgentCancellationService(repository);
        loopGuard = new AgentLoopGuard();
        approvals = mock(AgentApprovalService.class);
        modelExecutor = mock(RoutingAgentModelExecutor.class);
        sanitizer = new AgentToolResultSanitizer(json);

        // 注册只读工具（ITERATION_PLANNING skill 允许 list_tasks 和 get_task）
        AgentTool listTasksTool = readOnlyTool("list_tasks");
        AgentTool getTaskTool = readOnlyTool("get_task");
        tools = new AgentToolRegistry(List.of(listTasksTool, getTaskTool));

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

    /**
     * 第一次 advance：模型返回 Tool Call，工具只执行一次，Run 进入 QUEUED。
     * 第二次 advance：从 Repository 恢复历史，模型返回最终文本，Run 成功。
     */
    @Test
    void crossTickToolCallThenFinalAnswer() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 第一次 advance：模型返回 tool call
        ModelToolCall tc = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult turn1 = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(stepStore);

        AgentWorkerOutcome outcome1 = coordinator.advance(run);

        // 工具执行后，重新排队
        assertThat(outcome1.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(repository).requeueRun(run);

        // 模拟下一次 RuntimeJob 重新领取该 Run
        // 从 Repository 恢复历史消息和步骤
        AgentStepView toolStep = toolCompletedStep(1, "list_tasks", tc.arguments(), json.createObjectNode().put("success", true));
        stepStore.add(toolStep);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(new ArrayList<>(stepStore));

        // 第二次 advance：模型返回最终文本
        ModelTurnResult turn2 = textResult("查询完成，项目有 3 个任务");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn2);

        AgentWorkerOutcome outcome2 = coordinator.advance(run);

        // Run 成功
        assertThat(outcome2.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome2.answer()).isEqualTo("查询完成，项目有 3 个任务");

        // 工具只执行一次（第二次没有再次执行）
        verify(repository, times(1)).requeueRun(run);
        verify(repository).recordFinal(eq(run), eq("查询完成，项目有 3 个任务"), any());
    }

    /**
     * 单轮多个 Tool Call。
     */
    @Test
    void singleRoundMultipleToolCalls() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 模型返回两个 tool call
        ModelToolCall tc1 = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelToolCall tc2 = new ModelToolCall("call-2", "get_task",
                json.createObjectNode().put("taskId", UUID.randomUUID().toString()));
        ModelTurnResult turn = toolCallResult(tc1, tc2);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(stepStore);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // 两个工具都执行后，重新排队
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(repository, times(2)).recordToolResult(any(), anyString(), any(), any(), eq(false));
        verify(repository).requeueRun(run);
    }

    /**
     * 连续两个不同工具（跨 Tick）。
     */
    @Test
    void consecutiveDifferentTools() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 第一次 advance：第一个工具
        ModelToolCall tc1 = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult turn1 = toolCallResult(tc1);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(stepStore);

        coordinator.advance(run);

        // 恢复历史
        AgentStepView step1 = toolCompletedStep(1, "list_tasks", tc1.arguments(), json.createObjectNode());
        stepStore.add(step1);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(new ArrayList<>(stepStore));

        // 第二次 advance：第二个工具
        ObjectNode getTaskArgs = json.createObjectNode().put("taskId", UUID.randomUUID().toString());
        ModelToolCall tc2 = new ModelToolCall("call-2", "get_task", getTaskArgs);
        ModelTurnResult turn2 = toolCallResult(tc2);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn2);

        coordinator.advance(run);

        // 恢复两个步骤的历史
        AgentStepView step2 = toolCompletedStep(2, "get_task", getTaskArgs, json.createObjectNode());
        stepStore.add(step2);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(new ArrayList<>(stepStore));

        // 第三次 advance：最终答案
        ModelTurnResult turn3 = textResult("完成");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn3);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        // 两个工具都被记录
        verify(repository, times(2)).recordToolResult(any(), anyString(), any(), any(), eq(false));
        verify(repository).recordFinal(eq(run), eq("完成"), any());
    }

    /**
     * 未知工具返回错误 Tool Result。
     */
    @Test
    void unknownToolReturnsErrorResult() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 模型调用一个不在注册表中的工具
        ModelToolCall tc = new ModelToolCall("call-1", "nonexistent_tool", json.createObjectNode());
        ModelTurnResult turn = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(stepStore);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // 未知工具返回错误，然后重新排队
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(repository).recordToolResult(eq(run), eq("nonexistent_tool"), any(),
                argThat(node -> node.has("error")), eq(true));
    }

    /**
     * 工具执行失败时返回安全的错误 Tool Result。
     * 使用一个会抛异常的只读工具。
     */
    @Test
    void toolExecutionFailureReturnsSafeErrorResult() {
        // 创建一个会抛异常的工具
        AgentTool failingTool = new AgentTool() {
            @Override public String name() { return "list_tasks"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                throw new RuntimeException("数据库连接超时");
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(failingTool));
        coordinator = new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService,
                registry, cancellation, loopGuard, approvals, modelExecutor, sanitizer, json);

        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        ModelToolCall tc = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult turn = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(stepStore);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // 执行失败返回错误，然后重新排队
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(repository).recordToolResult(eq(run), eq("list_tasks"), any(),
                argThat(node -> node.has("error")), eq(true));
    }

    /**
     * Tool Result 与 toolCallId 一一对应。
     */
    @Test
    void toolResultMatchesToolCallId() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 多个 tool call 使用不同的 ID
        ModelToolCall tc1 = new ModelToolCall("unique-id-1", "list_tasks", json.createObjectNode());
        ModelToolCall tc2 = new ModelToolCall("unique-id-2", "get_task",
                json.createObjectNode().put("taskId", UUID.randomUUID().toString()));
        ModelTurnResult turn = toolCallResult(tc1, tc2);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(stepStore);

        coordinator.advance(run);

        // 验证每个 tool call 被正确记录，input 包含 toolCallId 和 arguments
        verify(repository).recordToolResult(eq(run), eq("list_tasks"),
                argThat(args -> args.has("toolCallId")
                        && args.get("toolCallId").asText().equals("unique-id-1")
                        && args.get("arguments").equals(tc1.arguments())),
                any(), eq(false));
        verify(repository).recordToolResult(eq(run), eq("get_task"),
                argThat(args -> args.has("toolCallId")
                        && args.get("toolCallId").asText().equals("unique-id-2")
                        && args.get("arguments").equals(tc2.arguments())),
                any(), eq(false));
    }

    /**
     * 跨 Tick 后 Tool Result 不会重复添加。
     */
    @Test
    void crossTickToolResultsNotDuplicated() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 第一次 advance
        ModelToolCall tc = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult turn1 = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(stepStore);

        coordinator.advance(run);

        // 恢复历史
        AgentStepView step = toolCompletedStep(1, "list_tasks", tc.arguments(), json.createObjectNode());
        stepStore.add(step);
        when(repository.listSteps(run.projectId(), run.id())).thenReturn(new ArrayList<>(stepStore));

        // 第二次 advance - 模型直接返回最终答案
        ModelTurnResult turn2 = textResult("完成");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn2);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        // 工具只被记录一次
        verify(repository, times(1)).recordToolResult(
                eq(run), eq("list_tasks"), any(), any(), eq(false));
    }

    // ========== 辅助方法 ==========

    private AgentRunView runWithSkillCode(String skillCode) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, skillCode, 1, now, now);
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

    private AgentStepView toolCompletedStep(int sequence, String toolName,
                                             JsonNode input, JsonNode output) {
        return new AgentStepView(
                UUID.randomUUID(), sequence, AgentStepType.TOOL_CALL_COMPLETED,
                toolName, input, output, "TOOL_SUCCESS",
                null, null, false, null, null, OffsetDateTime.now());
    }

    private AgentTool readOnlyTool(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
        };
    }
}
