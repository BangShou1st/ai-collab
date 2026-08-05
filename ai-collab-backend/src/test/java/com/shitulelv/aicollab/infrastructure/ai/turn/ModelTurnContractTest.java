package com.shitulelv.aicollab.infrastructure.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelToolDefinition;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一模型轮次合同测试。
 * 覆盖：纯文本结果、单个/多个 Tool Call、文本与 Tool Call 并存、
 * null List 转空、防御性复制、非法参数拒绝、Tool Result ID 关联、
 * FinishReason 映射、Usage 缺失处理。
 */
class ModelTurnContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    // ========== ModelToolCall ==========

    @Test
    void toolCallWithValidArguments() {
        ObjectNode args = mapper.createObjectNode().put("key", "value");
        ModelToolCall call = new ModelToolCall("call-1", "test_tool", args);
        assertThat(call.id()).isEqualTo("call-1");
        assertThat(call.name()).isEqualTo("test_tool");
        assertThat(call.arguments().isObject()).isTrue();
        assertThat(call.arguments().path("key").asText()).isEqualTo("value");
    }

    @Test
    void toolCallDeepCopiesArguments() {
        ObjectNode args = mapper.createObjectNode().put("key", "value");
        ModelToolCall call = new ModelToolCall("call-1", "test_tool", args);
        args.put("key", "modified");
        assertThat(call.arguments().path("key").asText()).isEqualTo("value");
    }

    @Test
    void toolCallRejectsNullId() {
        assertThatThrownBy(() -> new ModelToolCall(null, "test", mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolCallRejectsBlankId() {
        assertThatThrownBy(() -> new ModelToolCall("  ", "test", mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolCallRejectsNullName() {
        assertThatThrownBy(() -> new ModelToolCall("id", null, mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolCallRejectsNullArguments() {
        assertThatThrownBy(() -> new ModelToolCall("id", "test", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolCallRejectsNonObjectArguments() {
        assertThatThrownBy(() -> new ModelToolCall("id", "test", mapper.createArrayNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========== ModelMessage ==========

    @Test
    void systemMessageStripsWhitespace() {
        ModelMessage.System msg = new ModelMessage.System("  hello  ");
        assertThat(msg.content()).isEqualTo("hello");
    }

    @Test
    void systemMessageRejectsNull() {
        assertThatThrownBy(() -> new ModelMessage.System(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemMessageRejectsBlank() {
        assertThatThrownBy(() -> new ModelMessage.System("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userMessageStripsWhitespace() {
        ModelMessage.User msg = new ModelMessage.User("  hello  ");
        assertThat(msg.content()).isEqualTo("hello");
    }

    @Test
    void assistantMessageWithContentOnly() {
        ModelMessage.Assistant msg = new ModelMessage.Assistant("hello", null);
        assertThat(msg.content()).isEqualTo("hello");
        assertThat(msg.toolCalls()).isEmpty();
    }

    @Test
    void assistantMessageWithToolCallsOnly() {
        ObjectNode args = mapper.createObjectNode();
        ModelToolCall call = new ModelToolCall("c1", "tool1", args);
        ModelMessage.Assistant msg = new ModelMessage.Assistant(null, List.of(call));
        assertThat(msg.content()).isEmpty();
        assertThat(msg.toolCalls()).hasSize(1);
    }

    @Test
    void assistantMessageWithContentAndToolCalls() {
        ObjectNode args = mapper.createObjectNode();
        ModelToolCall call = new ModelToolCall("c1", "tool1", args);
        ModelMessage.Assistant msg = new ModelMessage.Assistant("thinking", List.of(call));
        assertThat(msg.content()).isEqualTo("thinking");
        assertThat(msg.toolCalls()).hasSize(1);
    }

    @Test
    void assistantMessageRejectsEmptyContentAndToolCalls() {
        assertThatThrownBy(() -> new ModelMessage.Assistant("", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assistantMessageDeepCopiesToolCalls() {
        ObjectNode args = mapper.createObjectNode();
        List<ModelToolCall> calls = new ArrayList<>();
        calls.add(new ModelToolCall("c1", "tool1", args));
        ModelMessage.Assistant msg = new ModelMessage.Assistant("text", calls);
        calls.clear();
        assertThat(msg.toolCalls()).hasSize(1);
    }

    @Test
    void assistantMessageNullToolCallsBecomesEmpty() {
        ModelMessage.Assistant msg = new ModelMessage.Assistant("text", null);
        assertThat(msg.toolCalls()).isEmpty();
    }

    @Test
    void toolResultMessageValid() {
        ObjectNode result = mapper.createObjectNode().put("success", true);
        ModelMessage.ToolResult msg = new ModelMessage.ToolResult("c1", "tool1", result, false);
        assertThat(msg.toolCallId()).isEqualTo("c1");
        assertThat(msg.toolName()).isEqualTo("tool1");
        assertThat(msg.error()).isFalse();
    }

    @Test
    void toolResultRejectsNullToolCallId() {
        assertThatThrownBy(() -> new ModelMessage.ToolResult(
                null, "tool1", mapper.createObjectNode(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolResultRejectsNullToolName() {
        assertThatThrownBy(() -> new ModelMessage.ToolResult(
                "c1", null, mapper.createObjectNode(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolResultRejectsNullResult() {
        assertThatThrownBy(() -> new ModelMessage.ToolResult("c1", "tool1", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========== ModelTurnResult ==========

    @Test
    void turnResultTextOnly() {
        ModelTurnResult result = new ModelTurnResult(
                "hello", List.of(), ModelFinishReason.STOP,
                null, "test", "model", 100L);
        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(result.usage()).isNull();
    }

    @Test
    void turnResultToolCallsOnly() {
        ObjectNode args = mapper.createObjectNode();
        ModelToolCall call = new ModelToolCall("c1", "tool1", args);
        ModelTurnResult result = new ModelTurnResult(
                null, List.of(call), ModelFinishReason.TOOL_CALLS,
                null, "test", "model", 100L);
        assertThat(result.content()).isEmpty();
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
    }

    @Test
    void turnResultMultipleToolCalls() {
        ObjectNode args = mapper.createObjectNode();
        ModelToolCall call1 = new ModelToolCall("c1", "tool1", args);
        ModelToolCall call2 = new ModelToolCall("c2", "tool2", args);
        ModelTurnResult result = new ModelTurnResult(
                "thinking", List.of(call1, call2), ModelFinishReason.TOOL_CALLS,
                new ModelUsage(100, 50), "test", "model", 100L);
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.usage().inputTokens()).isEqualTo(100);
        assertThat(result.usage().outputTokens()).isEqualTo(50);
    }

    @Test
    void turnResultRejectsEmptyContentAndToolCalls() {
        assertThatThrownBy(() -> new ModelTurnResult(
                "", List.of(), ModelFinishReason.STOP,
                null, "test", "model", 100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void turnResultNullContentBecomesEmpty() {
        ObjectNode args = mapper.createObjectNode();
        ModelToolCall call = new ModelToolCall("c1", "tool1", args);
        ModelTurnResult result = new ModelTurnResult(
                null, List.of(call), ModelFinishReason.TOOL_CALLS,
                null, "test", "model", 100L);
        assertThat(result.content()).isEmpty();
    }

    @Test
    void turnResultNullToolCallsBecomesEmpty() {
        ModelTurnResult result = new ModelTurnResult(
                "hello", null, ModelFinishReason.STOP,
                null, "test", "model", 100L);
        assertThat(result.toolCalls()).isEmpty();
    }

    @Test
    void turnResultDeepCopiesToolCalls() {
        ObjectNode args = mapper.createObjectNode();
        List<ModelToolCall> calls = new ArrayList<>();
        calls.add(new ModelToolCall("c1", "tool1", args));
        ModelTurnResult result = new ModelTurnResult(
                "text", calls, ModelFinishReason.STOP,
                null, "test", "model", 100L);
        calls.clear();
        assertThat(result.toolCalls()).hasSize(1);
    }

    // ========== ModelUsage ==========

    @Test
    void usageWithNullTokens() {
        ModelUsage usage = new ModelUsage(null, null);
        assertThat(usage.inputTokens()).isNull();
        assertThat(usage.outputTokens()).isNull();
    }

    @Test
    void usageRejectsNegativeInputTokens() {
        assertThatThrownBy(() -> new ModelUsage(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usageRejectsNegativeOutputTokens() {
        assertThatThrownBy(() -> new ModelUsage(10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========== ModelTurnCommand ==========

    @Test
    void commandWithValidMessages() {
        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.System("sys"), new ModelMessage.User("hi")),
                List.of(),
                false);
        assertThat(cmd.purpose()).isEqualTo(ModelPurpose.AGENT);
        assertThat(cmd.messages()).hasSize(2);
        assertThat(cmd.tools()).isEmpty();
        assertThat(cmd.toolsRequired()).isFalse();
    }

    @Test
    void commandNullPurposeDefaultsToAgent() {
        ModelTurnCommand cmd = new ModelTurnCommand(
                null, List.of(new ModelMessage.User("hi")), List.of(), false);
        assertThat(cmd.purpose()).isEqualTo(ModelPurpose.AGENT);
    }

    @Test
    void commandNullMessagesBecomesEmpty() {
        assertThatThrownBy(() -> new ModelTurnCommand(
                ModelPurpose.AGENT, null, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandRejectsEmptyMessages() {
        assertThatThrownBy(() -> new ModelTurnCommand(
                ModelPurpose.AGENT, List.of(), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandRejectsNullInMessagesList() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(null);
        assertThatThrownBy(() -> new ModelTurnCommand(
                ModelPurpose.AGENT, messages, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandToolsRequiredButEmptyToolsThrows() {
        assertThatThrownBy(() -> new ModelTurnCommand(
                ModelPurpose.AGENT,
                List.of(new ModelMessage.User("hi")),
                List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandDeepCopiesMessages() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage.User("hi"));
        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT, messages, List.of(), false);
        messages.clear();
        assertThat(cmd.messages()).hasSize(1);
    }

    @Test
    void commandDeepCopiesTools() {
        JsonNode schema = mapper.createObjectNode().put("type", "object");
        List<ModelToolDefinition> tools = new ArrayList<>();
        tools.add(new ModelToolDefinition("t", "d", schema));
        ModelTurnCommand cmd = new ModelTurnCommand(
                ModelPurpose.AGENT, List.of(new ModelMessage.User("hi")), tools, false);
        tools.clear();
        assertThat(cmd.tools()).hasSize(1);
    }

    // ========== ModelFinishReason ==========

    @Test
    void finishReasonValues() {
        assertThat(ModelFinishReason.values()).hasSize(6);
        assertThat(ModelFinishReason.STOP).isNotNull();
        assertThat(ModelFinishReason.TOOL_CALLS).isNotNull();
        assertThat(ModelFinishReason.LENGTH).isNotNull();
        assertThat(ModelFinishReason.CONTENT_FILTER).isNotNull();
        assertThat(ModelFinishReason.ERROR).isNotNull();
        assertThat(ModelFinishReason.UNKNOWN).isNotNull();
    }
}
