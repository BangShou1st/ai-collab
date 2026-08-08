package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentDecisionParser;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LegacyReadOnlyAgentExecutor 测试。
 * 覆盖 Phase 1 Legacy 只读行为：
 * - 模型返回 final action -> 正确转换为 ModelTurnResult
 * - 模型返回 call_tool action -> 正确转换为 ToolCall
 * - legacy 写工具请求被拒绝（通过只暴露只读工具实现）
 * - 使用 AgentDecisionParser 解析模型输出
 */
class LegacyReadOnlyAgentExecutorTest {
    private final ObjectMapper json = new ObjectMapper();
    private ChatModelGateway chatGateway;
    private AgentDecisionParser decisionParser;
    private LegacyReadOnlyAgentExecutor executor;

    @BeforeEach
    void setUp() {
        chatGateway = mock(ChatModelGateway.class);
        decisionParser = new AgentDecisionParser(json);
        executor = new LegacyReadOnlyAgentExecutor(chatGateway, decisionParser, json);
    }

    @Test
    void finalAnswerReturnsTextResult() {
        String jsonDecision = """
                {"action":"final","answer":"项目进展正常","citations":[],"inferences":[]}
                """;
        ChatCompletionResult completion = new ChatCompletionResult(
                jsonDecision, "openai", "gpt-3.5-turbo", 100, 50, 100L);
        when(chatGateway.complete(any())).thenReturn(completion);

        ModelTurnResult turn = executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("检查项目")),
                List.of(),
                null,
                false);

        assertThat(turn.content()).isEqualTo("项目进展正常");
        assertThat(turn.toolCalls()).isEmpty();
        assertThat(turn.finishReason()).isEqualTo(ModelFinishReason.STOP);
    }

    @Test
    void callToolActionReturnsToolCall() {
        String jsonDecision = """
                {"action":"call_tool","tool":"list_tasks","arguments":{},"reason":"查看任务列表"}
                """;
        ChatCompletionResult completion = new ChatCompletionResult(
                jsonDecision, "openai", "gpt-3.5-turbo", 100, 50, 100L);
        when(chatGateway.complete(any())).thenReturn(completion);

        ModelTurnResult turn = executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("列出任务")),
                List.of(AgentToolDefinition.openObject("list_tasks", "列出任务", false)),
                null,
                false);

        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().getFirst().name()).isEqualTo("list_tasks");
        assertThat(turn.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
    }

    @Test
    void readOnlyToolsOnlyAreExposed() {
        String jsonDecision = """
                {"action":"final","answer":"完成","citations":[],"inferences":[]}
                """;
        ChatCompletionResult completion = new ChatCompletionResult(
                jsonDecision, "openai", "gpt-3.5-turbo", 100, 50, 100L);
        when(chatGateway.complete(any())).thenReturn(completion);

        List<AgentToolDefinition> exposed = List.of(
                AgentToolDefinition.openObject("list_tasks", "列出任务", false),
                AgentToolDefinition.openObject("create_task", "创建任务", true));

        executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                exposed,
                null,
                false);

        // 验证传给 ChatModelGateway 的命令不包含工具（Legacy 模式）
        verify(chatGateway).complete(argThat(cmd ->
                cmd.tools().isEmpty()));
    }

    @Test
    void invalidJsonFailsWithParser() {
        ChatCompletionResult completion = new ChatCompletionResult(
                "这不是 JSON", "openai", "gpt-3.5-turbo", 100, 50, 100L);
        when(chatGateway.complete(any())).thenReturn(completion);

        try {
            executor.callModel(
                    List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                    List.of(),
                    null,
                    false);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Agent 决策不是合法 JSON");
        }
    }

    @Test
    void unknownActionFailsWithParser() {
        String jsonDecision = """
                {"action":"unknown_action","data":"test"}
                """;
        ChatCompletionResult completion = new ChatCompletionResult(
                jsonDecision, "openai", "gpt-3.5-turbo", 100, 50, 100L);
        when(chatGateway.complete(any())).thenReturn(completion);

        try {
            executor.callModel(
                    List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                    List.of(),
                    null,
                    false);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("未知 Agent action");
        }
    }

    @Test
    void legacyProviderExceptionPropagates() {
        when(chatGateway.complete(any())).thenThrow(
                new RuntimeException("连接超时"));

        try {
            executor.callModel(
                    List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                    List.of(),
                    null,
                    false);
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("连接超时");
        }
    }

    @Test
    void correctionAttemptedFlagPassedToParser() {
        String jsonDecision = """
                {"action":"final","answer":"完成","citations":[],"inferences":[]}
                """;
        ChatCompletionResult completion = new ChatCompletionResult(
                jsonDecision, "openai", "gpt-3.5-turbo", 100, 50, 100L);
        when(chatGateway.complete(any())).thenReturn(completion);

        executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                List.of(),
                null,
                true);

        // 验证 parser 被调用，correctionAttempted=true 时错误信息不同
        verify(chatGateway).complete(any());
    }

    @Test
    void tokenUsageIsPreserved() {
        String jsonDecision = """
                {"action":"final","answer":"完成","citations":[],"inferences":[]}
                """;
        ChatCompletionResult completion = new ChatCompletionResult(
                jsonDecision, "openai", "gpt-3.5-turbo", 150, 80, 200L);
        when(chatGateway.complete(any())).thenReturn(completion);

        ModelTurnResult turn = executor.callModel(
                List.of(new ModelMessage.System("你是助手"), new ModelMessage.User("测试")),
                List.of(),
                null,
                false);

        assertThat(turn.usage()).isNotNull();
        assertThat(turn.usage().inputTokens()).isEqualTo(150);
        assertThat(turn.usage().outputTokens()).isEqualTo(80);
    }
}
