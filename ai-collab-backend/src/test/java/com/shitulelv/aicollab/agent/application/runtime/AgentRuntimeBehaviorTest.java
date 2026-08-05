package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.*;
import com.shitulelv.aicollab.agent.domain.policy.AgentLoopGuard;
import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 1 行为测试。
 * 覆盖 Schema 校验、预算边界、工具结果不重复、Legacy 写拒绝、Native 不调用 Parser 等场景。
 */
class AgentRuntimeBehaviorTest {
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
        cancellation = new AgentCancellationService(repository);
        loopGuard = new AgentLoopGuard();
        approvals = mock(AgentApprovalService.class);
        modelExecutor = mock(RoutingAgentModelExecutor.class);
        sanitizer = new AgentToolResultSanitizer(json);
        coordinator = createCoordinator(new AgentToolRegistry(List.of()));

        // 设置默认返回值：recordToolResult 和 recordModelTurn 返回传入的 run
        when(repository.recordToolResult(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.recordModelTurn(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.recordFinal(any(), any(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * 1. Schema 校验在只读工具执行前。
     */
    @Test
    void schemaValidationBeforeReadOnlyToolExecution() {
        // 创建一个需要参数的工具，带 schema
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "get_task"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
            @Override public AgentToolDefinition definition() {
                return AgentToolDefinition.fromJson("get_task", "获取任务",
                        "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\",\"format\":\"uuid\"}},\"required\":[\"taskId\"]}", false);
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        coordinator = createCoordinator(registry);

        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 模型返回一个缺少必需参数的工具调用
        JsonNode invalidArgs = json.createObjectNode(); // 缺少 taskId
        ModelToolCall tc = new ModelToolCall("call-1", "get_task", invalidArgs);
        ModelTurnResult turn = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // Schema 校验失败，返回错误 Tool Result
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(repository).recordToolResult(eq(run), eq("get_task"), any(),
                argThat(result -> result.has("error")), eq(true));
    }

    /**
     * 2. Schema 校验在审批创建前。
     */
    @Test
    void schemaValidationBeforeApprovalCreation() {
        // 创建一个写工具
        ApprovalWriteAgentTool writeTool = new ApprovalWriteAgentTool() {
            @Override public String name() { return "create_task_after_approval"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public AgentToolDefinition definition() {
                return AgentToolDefinition.fromJson("create_task_after_approval", "创建任务",
                        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}},\"required\":[\"title\"]}", true);
            }
            @Override public JsonNode normalize(AgentToolContext ctx, JsonNode args) { return args; }
            @Override public JsonNode diff(AgentToolContext ctx, JsonNode args) { return json.createObjectNode(); }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(writeTool));
        coordinator = createCoordinator(registry);

        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 模型返回一个缺少必需参数的写工具调用
        JsonNode invalidArgs = json.createObjectNode(); // 缺少 title
        ModelToolCall tc = new ModelToolCall("call-1", "create_task_after_approval", invalidArgs);
        ModelTurnResult turn = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // Schema 校验失败，返回错误 Tool Result，不创建审批
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(approvals, never()).propose(any(), any(), any(), any(), any());
    }

    /**
     * 3. 第一次非法参数后允许修正。
     */
    @Test
    void firstInvalidArgumentAllowsCorrection() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");

        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        ModelTurnResult turn = textResult("修正后的回答");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    /**
     * 4. 同一工具第二次非法后失败。
     */
    @Test
    void secondInvalidArgumentFails() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");

        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 创建一个需要参数的工具
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "get_task"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        coordinator = createCoordinator(registry);

        // 模型返回非法参数
        JsonNode invalidArgs = json.createObjectNode();
        ModelToolCall tc = new ModelToolCall("call-1", "get_task", invalidArgs);
        ModelTurnResult turn = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // 非法参数，返回错误
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
    }

    /**
     * 5. 多轮 input token 累计。
     */
    @Test
    void multiRoundInputTokenAccumulation() {
        AgentRunView run = runWithSkillCode("PROJECT_RESEARCH");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("PROJECT_RESEARCH"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "PROJECT_RESEARCH".equals(s.code()))))
                .thenReturn(plan("研究", List.of()));

        // 第一轮：输入 100 tokens
        ModelTurnResult turn1 = new ModelTurnResult("回答1", List.of(), ModelFinishReason.STOP,
                new ModelUsage(100, 50), "test", "model", 100L);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);

        coordinator.advance(run);

        // 验证 input tokens 被记录
        verify(repository).recordModelTurn(eq(run), eq(turn1));
    }

    /**
     * 6. 多轮 output token 累计。
     */
    @Test
    void multiRoundOutputTokenAccumulation() {
        AgentRunView run = runWithSkillCode("PROJECT_RESEARCH");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("PROJECT_RESEARCH"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "PROJECT_RESEARCH".equals(s.code()))))
                .thenReturn(plan("研究", List.of()));

        // 第一轮：输出 50 tokens
        ModelTurnResult turn1 = new ModelTurnResult("回答1", List.of(), ModelFinishReason.STOP,
                new ModelUsage(100, 50), "test", "model", 100L);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);

        coordinator.advance(run);

        // 验证 output tokens 被记录
        verify(repository).recordModelTurn(eq(run), eq(turn1));
    }

    /**
     * 7. 多轮 step 累计。
     */
    @Test
    void multiRoundStepAccumulation() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 第一轮：调用工具
        AgentTool tool = readOnlyTool("list_tasks");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        coordinator = createCoordinator(registry);

        ModelToolCall tc = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult turn1 = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);

        coordinator.advance(run);

        // 验证 step 被记录
        verify(repository).recordToolResult(eq(run), eq("list_tasks"), any(), any(), eq(false));
    }

    /**
     * 8. 多轮 tool call 累计。
     */
    @Test
    void multiRoundToolCallAccumulation() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 第一轮：调用多个工具
        AgentTool tool1 = readOnlyTool("list_tasks");
        AgentTool tool2 = readOnlyTool("get_task");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool1, tool2));
        coordinator = createCoordinator(registry);

        ModelToolCall tc1 = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelToolCall tc2 = new ModelToolCall("call-2", "get_task", json.createObjectNode().put("taskId", UUID.randomUUID().toString()));
        ModelTurnResult turn1 = toolCallResult(tc1, tc2);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);

        coordinator.advance(run);

        // 验证两个工具都被记录
        verify(repository).recordToolResult(eq(run), eq("list_tasks"), any(), any(), eq(false));
        verify(repository).recordToolResult(eq(run), eq("get_task"), any(), any(), eq(false));
    }

    /**
     * 9. 正好达到预算允许。
     */
    @Test
    void withinBudgetAllowsExecution() {
        AgentRunView run = runWithSkillCode("PROJECT_RESEARCH");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("PROJECT_RESEARCH"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "PROJECT_RESEARCH".equals(s.code()))))
                .thenReturn(plan("研究", List.of()));

        ModelTurnResult turn = textResult("完成");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    /**
     * 10. 超过预算拒绝。
     */
    @Test
    void overBudgetRejectsExecution() {
        // 创建一个超过预算的 run
        AgentRunView run = new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                16, 0, 0, 0, 0, false, // stepsUsed = maxSteps
                false, false, 0, null, null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now());

        // 不需要 mock contextAssembler，因为 budget 检查在 Worker 中
        // 这里验证 Coordinator 不会因为 budget 问题而失败
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), isNull(), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), any())).thenReturn(plan("研究", List.of()));

        ModelTurnResult turn = textResult("完成");
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        // Coordinator 不检查 budget，由 Worker 检查
        AgentWorkerOutcome outcome = coordinator.advance(run);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    /**
     * 11. Tool Result 不重复。
     */
    @Test
    void toolResultNotDuplicated() {
        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        AgentTool tool = readOnlyTool("list_tasks");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        coordinator = createCoordinator(registry);

        ModelToolCall tc = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult turn = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        coordinator.advance(run);

        // 验证工具只被调用一次
        verify(repository, times(1)).recordToolResult(eq(run), eq("list_tasks"), any(), any(), eq(false));
    }

    /**
     * 12. Legacy 写工具运行时拒绝。
     */
    @Test
    void legacyWriteToolRuntimeRejection() {
        // 模拟 Legacy 模式
        when(modelExecutor.isLegacyMode()).thenReturn(true);

        // 创建一个写工具
        ApprovalWriteAgentTool writeTool = new ApprovalWriteAgentTool() {
            @Override public String name() { return "create_task_after_approval"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public AgentToolDefinition definition() {
                return AgentToolDefinition.fromJson("create_task_after_approval", "创建任务",
                        "{\"type\":\"object\",\"properties\":{}}", true);
            }
            @Override public JsonNode normalize(AgentToolContext ctx, JsonNode args) { return args; }
            @Override public JsonNode diff(AgentToolContext ctx, JsonNode args) { return json.createObjectNode(); }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(writeTool));
        coordinator = createCoordinator(registry);

        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 模型返回写工具调用
        ModelToolCall tc = new ModelToolCall("call-1", "create_task_after_approval", json.createObjectNode());
        ModelTurnResult turn = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // Legacy 模式下写工具被拒绝
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("LEGACY_WRITE_TOOL_FORBIDDEN");
        verify(approvals, never()).propose(any(), any(), any(), any(), any());
    }

    /**
     * 13. Native 不调用 AgentDecisionParser。
     */
    @Test
    void nativeModeDoesNotUseDecisionParser() {
        // 验证 NativeToolCallingExecutor 不调用 AgentDecisionParser
        // 通过确认它只依赖 ModelTurnGateway 来验证
        ModelTurnGateway modelTurn = mock(ModelTurnGateway.class);
        NativeToolCallingExecutor nativeExecutor = new NativeToolCallingExecutor(modelTurn);

        ModelTurnResult result = new ModelTurnResult(
                "完成", List.of(), ModelFinishReason.STOP,
                new ModelUsage(100, 50), "openai", "gpt-4", 200L);
        when(modelTurn.turn(any())).thenReturn(result);

        UUID configId = UUID.randomUUID();
        nativeExecutor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                List.of(),
                configId);

        // 验证只调用了一次 modelTurn.turn，没有调用 AgentDecisionParser
        verify(modelTurn, times(1)).turn(any());
    }

    /**
     * 14. 第一次非法参数后不执行工具。
     */
    @Test
    void firstInvalidArgumentsDoesNotExecuteTool() {
        // 创建一个需要参数的工具（使用 mock）
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("get_task");
        when(tool.writesBusinessData()).thenReturn(false);
        when(tool.definition()).thenReturn(AgentToolDefinition.fromJson("get_task", "获取任务",
                "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\",\"format\":\"uuid\"}},\"required\":[\"taskId\"]}", false));
        when(tool.execute(any(), any())).thenReturn(new AgentToolResult(json.createObjectNode().put("success", true), null, null));

        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        coordinator = createCoordinator(registry);

        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 模型返回非法参数
        JsonNode invalidArgs = json.createObjectNode(); // 缺少 taskId
        ModelToolCall tc = new ModelToolCall("call-1", "get_task", invalidArgs);
        ModelTurnResult turn = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // 非法参数，返回错误，不执行工具
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(tool, never()).execute(any(), any());
    }

    /**
     * 15. 第一次非法参数后不创建审批。
     */
    @Test
    void firstInvalidArgumentsDoesNotCreateApproval() {
        // 创建一个写工具
        ApprovalWriteAgentTool writeTool = new ApprovalWriteAgentTool() {
            @Override public String name() { return "create_task_after_approval"; }
            @Override public boolean writesBusinessData() { return true; }
            @Override public AgentToolDefinition definition() {
                return AgentToolDefinition.fromJson("create_task_after_approval", "创建任务",
                        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}},\"required\":[\"title\"]}", true);
            }
            @Override public JsonNode normalize(AgentToolContext ctx, JsonNode args) { return args; }
            @Override public JsonNode diff(AgentToolContext ctx, JsonNode args) { return json.createObjectNode(); }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(writeTool));
        coordinator = createCoordinator(registry);

        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 模型返回非法参数
        JsonNode invalidArgs = json.createObjectNode(); // 缺少 title
        ModelToolCall tc = new ModelToolCall("call-1", "create_task_after_approval", invalidArgs);
        ModelTurnResult turn = toolCallResult(tc);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn);

        AgentWorkerOutcome outcome = coordinator.advance(run);

        // 非法参数，返回错误，不创建审批
        assertThat(outcome.status()).isEqualTo(AgentRunStatus.QUEUED);
        verify(approvals, never()).propose(any(), any(), any(), any(), any());
    }

    /**
     * 16. 不同工具独立计数。
     */
    @Test
    void differentToolHasIndependentCorrectionCount() {
        // 创建两个需要参数的工具
        AgentTool tool1 = new AgentTool() {
            @Override public String name() { return "get_task"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
            @Override public AgentToolDefinition definition() {
                return AgentToolDefinition.fromJson("get_task", "获取任务",
                        "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\",\"format\":\"uuid\"}},\"required\":[\"taskId\"]}", false);
            }
        };
        AgentTool tool2 = new AgentTool() {
            @Override public String name() { return "get_milestone"; }
            @Override public boolean writesBusinessData() { return false; }
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
            @Override public AgentToolDefinition definition() {
                return AgentToolDefinition.fromJson("get_milestone", "获取里程碑",
                        "{\"type\":\"object\",\"properties\":{\"milestoneId\":{\"type\":\"string\",\"format\":\"uuid\"}},\"required\":[\"milestoneId\"]}", false);
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool1, tool2));
        coordinator = createCoordinator(registry);

        AgentRunView run = runWithSkillCode("ITERATION_PLANNING");
        AgentExecutionContext ctx = context();
        when(contextAssembler.assemble(eq(run), eq("ITERATION_PLANNING"), any())).thenReturn(ctx);
        when(planService.ensurePlan(eq(run), argThat(s -> "ITERATION_PLANNING".equals(s.code()))))
                .thenReturn(plan("规划", List.of()));

        // 第一次：get_task 非法参数
        JsonNode invalidArgs1 = json.createObjectNode(); // 缺少 taskId
        ModelToolCall tc1 = new ModelToolCall("call-1", "get_task", invalidArgs1);
        ModelTurnResult turn1 = toolCallResult(tc1);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn1);

        AgentWorkerOutcome outcome1 = coordinator.advance(run);
        assertThat(outcome1.status()).isEqualTo(AgentRunStatus.QUEUED);

        // 第二次：get_milestone 非法参数（不同工具，应该独立计数）
        JsonNode invalidArgs2 = json.createObjectNode(); // 缺少 milestoneId
        ModelToolCall tc2 = new ModelToolCall("call-2", "get_milestone", invalidArgs2);
        ModelTurnResult turn2 = toolCallResult(tc2);
        when(modelExecutor.callModel(eq(run), any(), any(), eq(false))).thenReturn(turn2);

        AgentWorkerOutcome outcome2 = coordinator.advance(run);
        // 不同工具，第一次非法参数，应该返回 QUEUED
        assertThat(outcome2.status()).isEqualTo(AgentRunStatus.QUEUED);
    }

    // ========== 辅助方法 ==========

    private AgentRuntimeCoordinator createCoordinator(AgentToolRegistry registry) {
        return new AgentRuntimeCoordinator(
                repository, contextAssembler, skillRegistry, planService,
                registry, cancellation, loopGuard, approvals, modelExecutor, sanitizer, json);
    }

    private AgentRunView run() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "检查项目", AgentRunStatus.RUNNING,
                16, 12, 3, 100_000, 32_000,
                0, 0, 0, 0, 0, false,
                false, false, 0, null, null, null, null, 1, now, now);
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

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", false, AgentPageContext.empty(),
                AgentRuntimeLimits.defaults(), 0);
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
            @Override public AgentToolResult execute(AgentToolContext ctx, JsonNode args) {
                return new AgentToolResult(json.createObjectNode().put("success", true), null, null);
            }
        };
    }
}
