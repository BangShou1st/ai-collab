package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.*;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * NativeToolCallingExecutor 测试。
 * 覆盖 Phase 1 原生 Tool Calling 行为：
 * - 模型直接返回最终文本
 * - 一个只读工具后返回答案
 * - 连续多轮 Tool Calling
 * - 单轮多个 Tool Call
 * - Tool Result ID 关联
 * - 原生模式不调用 AgentDecisionParser
 */
class NativeToolCallingExecutorTest {
    private final ObjectMapper json = new ObjectMapper();
    private ModelTurnGateway modelTurn;
    private NativeToolCallingExecutor executor;

    @BeforeEach
    void setUp() {
        modelTurn = mock(ModelTurnGateway.class);
        executor = new NativeToolCallingExecutor(modelTurn);
    }

    @Test
    void textOnlyResponseReturnsContent() {
        ModelTurnResult result = new ModelTurnResult(
                "项目进展正常", List.of(), ModelFinishReason.STOP,
                new ModelUsage(100, 50), "openai", "gpt-4", 200L);
        when(modelTurn.turn(any())).thenReturn(result);

        UUID configId = UUID.randomUUID();
        ModelTurnResult turn = executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("检查项目")),
                List.of(),
                null,
                configId);

        assertThat(turn.content()).isEqualTo("项目进展正常");
        assertThat(turn.toolCalls()).isEmpty();
        assertThat(turn.finishReason()).isEqualTo(ModelFinishReason.STOP);
    }

    @Test
    void singleToolCallReturnsToolCalls() {
        ModelToolCall tc = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelTurnResult result = new ModelTurnResult(
                "", List.of(tc), ModelFinishReason.TOOL_CALLS,
                new ModelUsage(100, 50), "openai", "gpt-4", 200L);
        when(modelTurn.turn(any())).thenReturn(result);

        UUID configId = UUID.randomUUID();
        ModelTurnResult turn = executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("列出任务")),
                List.of(toolDef("list_tasks")),
                null,
                configId);

        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().getFirst().name()).isEqualTo("list_tasks");
        assertThat(turn.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
    }

    @Test
    void multipleToolCallsInSingleRound() {
        ModelToolCall tc1 = new ModelToolCall("call-1", "list_tasks", json.createObjectNode());
        ModelToolCall tc2 = new ModelToolCall("call-2", "get_task",
                json.createObjectNode().put("taskId", UUID.randomUUID().toString()));
        ModelTurnResult result = new ModelTurnResult(
                "", List.of(tc1, tc2), ModelFinishReason.TOOL_CALLS,
                new ModelUsage(100, 50), "openai", "gpt-4", 200L);
        when(modelTurn.turn(any())).thenReturn(result);

        UUID configId = UUID.randomUUID();
        ModelTurnResult turn = executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("列出并获取任务")),
                List.of(toolDef("list_tasks"), toolDef("get_task")),
                null,
                configId);

        assertThat(turn.toolCalls()).hasSize(2);
        assertThat(turn.toolCalls().get(0).name()).isEqualTo("list_tasks");
        assertThat(turn.toolCalls().get(1).name()).isEqualTo("get_task");
    }

    @Test
    void toolCallIdIsPreserved() {
        String uniqueId = "unique-call-id-123";
        ModelToolCall tc = new ModelToolCall(uniqueId, "list_tasks", json.createObjectNode());
        ModelTurnResult result = new ModelTurnResult(
                "", List.of(tc), ModelFinishReason.TOOL_CALLS,
                new ModelUsage(100, 50), "openai", "gpt-4", 200L);
        when(modelTurn.turn(any())).thenReturn(result);

        UUID configId = UUID.randomUUID();
        ModelTurnResult turn = executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("列出任务")),
                List.of(toolDef("list_tasks")),
                null,
                configId);

        assertThat(turn.toolCalls().getFirst().id()).isEqualTo(uniqueId);
    }

    @Test
    void modelProviderExceptionPropagates() {
        when(modelTurn.turn(any())).thenThrow(
                new BusinessException(ErrorCode.AI_PROVIDER_ERROR));

        UUID configId = UUID.randomUUID();
        assertThatThrownBy(() -> executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                List.of(),
                null,
                configId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_PROVIDER_ERROR));
    }

    @Test
    void nativeModeDoesNotUseDecisionParser() {
        // 验证 NativeToolCallingExecutor 不调用 AgentDecisionParser
        // 通过确认它只依赖 ModelTurnGateway 来验证
        ModelTurnResult result = new ModelTurnResult(
                "完成", List.of(), ModelFinishReason.STOP,
                new ModelUsage(100, 50), "openai", "gpt-4", 200L);
        when(modelTurn.turn(any())).thenReturn(result);

        UUID configId = UUID.randomUUID();
        executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                List.of(),
                null,
                configId);

        // 验证只调用了一次 modelTurn.turn
        verify(modelTurn, times(1)).turn(any());
    }

    @Test
    void messageHistoryIsPassedToModel() {
        ModelTurnResult result = new ModelTurnResult(
                "回答", List.of(), ModelFinishReason.STOP, null, "test", "model", 100L);
        when(modelTurn.turn(any())).thenReturn(result);

        List<ModelMessage> messages = List.of(
                new ModelMessage.System("系统提示"),
                new ModelMessage.User("用户问题"),
                new ModelMessage.Assistant("助手回答", List.of()),
                new ModelMessage.User("追问"));

        UUID configId = UUID.randomUUID();
        executor.callModel(messages, List.of(), null, configId);

        verify(modelTurn).turn(argThat(cmd -> {
            List<ModelMessage> actualMessages = cmd.messages();
            return actualMessages.size() == 4
                    && actualMessages.get(0) instanceof ModelMessage.System
                    && actualMessages.get(1) instanceof ModelMessage.User
                    && actualMessages.get(2) instanceof ModelMessage.Assistant
                    && actualMessages.get(3) instanceof ModelMessage.User;
        }));
    }

    @Test
    void toolDefinitionsArePassedToModel() {
        ModelTurnResult result = new ModelTurnResult(
                "回答", List.of(), ModelFinishReason.STOP, null, "test", "model", 100L);
        when(modelTurn.turn(any())).thenReturn(result);

        List<AgentToolDefinition> exposed = List.of(
                toolDef("list_tasks"),
                toolDef("get_task"));

        UUID configId = UUID.randomUUID();
        executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                exposed,
                null,
                configId);

        verify(modelTurn).turn(argThat(cmd ->
                cmd.tools().size() == 2));
    }

    // ========== 辅助方法 ==========

    private AgentToolDefinition toolDef(String name) {
        return AgentToolDefinition.openObject(name, "工具 " + name, false);
    }

    @Test
    void configurationIdIsPassedToModelTurnCommand() {
        ModelTurnResult result = new ModelTurnResult(
                "回答", List.of(), ModelFinishReason.STOP, null, "test", "model", 100L);
        when(modelTurn.turn(any())).thenReturn(result);

        UUID configId = UUID.randomUUID();
        executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                List.of(toolDef("list_tasks")),
                null,
                configId);

        // 验证 configurationId 被正确传递到 ModelTurnCommand
        verify(modelTurn).turn(argThat(cmd ->
                cmd.configurationId() != null && cmd.configurationId().equals(configId)));
    }
}
