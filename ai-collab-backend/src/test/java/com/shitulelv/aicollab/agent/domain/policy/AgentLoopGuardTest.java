package com.shitulelv.aicollab.agent.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentLoopGuard 测试。
 * 验证循环检测逻辑：
 * - 相同工具 + 相同参数 + 相同结果 = 无进展
 * - 不同工具或不同参数 = 有进展
 * - 跨 Tick 场景
 */
class AgentLoopGuardTest {
    private final ObjectMapper json = new ObjectMapper();
    private AgentLoopGuard guard;

    @BeforeEach
    void setUp() {
        guard = new AgentLoopGuard();
    }

    @Test
    void emptyStepsReturnsFalse() {
        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("task.search",
                json.createObjectNode().put("overdueOnly", false), "");
        assertThat(guard.hasNoProgress(List.of(), nextCall)).isFalse();
    }

    @Test
    void nullStepsReturnsFalse() {
        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("task.search",
                json.createObjectNode().put("overdueOnly", false), "");
        assertThat(guard.hasNoProgress(null, nextCall)).isFalse();
    }

    @Test
    void nullNextCallReturnsFalse() {
        assertThat(guard.hasNoProgress(List.of(toolStep("task.search", "{\"limit\":5}", "{\"items\":[]}")), null)).isFalse();
    }

    @Test
    void singleStepReturnsFalse() {
        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("task.search",
                json.createObjectNode().put("limit", 5), "");
        assertThat(guard.hasNoProgress(
                List.of(toolStep("task.search", "{\"limit\":5}", "{\"items\":[]}")),
                nextCall)).isFalse();
    }

    @Test
    void sameToolSameArgumentsSameResultStops() {
        // 两个相同步骤 + 相同的下一步 = 无进展
        ObjectNode args = json.createObjectNode().put("overdueOnly", false).put("limit", 5);
        ObjectNode result = json.createObjectNode().put("count", 3);

        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("task.search", args.deepCopy(), "");

        List<AgentStepView> steps = List.of(
                toolStep("task.search", args.deepCopy().toString(), result.deepCopy().toString()),
                toolStep("task.search", args.deepCopy().toString(), result.deepCopy().toString()));

        assertThat(guard.hasNoProgress(steps, nextCall)).isTrue();
    }

    @Test
    void differentArgumentsAreNotFalsePositiveLoop() {
        // 相同工具但不同参数 = 有进展
        ObjectNode args1 = json.createObjectNode().put("overdueOnly", false).put("limit", 5);
        ObjectNode args2 = json.createObjectNode().put("overdueOnly", true).put("limit", 10);
        ObjectNode result = json.createObjectNode().put("count", 3);

        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("task.search", args2.deepCopy(), "");

        List<AgentStepView> steps = List.of(
                toolStep("task.search", args1.deepCopy().toString(), result.deepCopy().toString()),
                toolStep("task.search", args1.deepCopy().toString(), result.deepCopy().toString()));

        // 不同参数 = 有进展
        assertThat(guard.hasNoProgress(steps, nextCall)).isFalse();
    }

    @Test
    void differentToolHasIndependentCorrectionCount() {
        // 不同工具 = 有进展
        ObjectNode result = json.createObjectNode().put("count", 3);

        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("milestone.list",
                json.createObjectNode(), "");

        List<AgentStepView> steps = List.of(
                toolStep("task.search", "{\"limit\":5}", result.deepCopy().toString()),
                toolStep("task.search", "{\"limit\":5}", result.deepCopy().toString()));

        assertThat(guard.hasNoProgress(steps, nextCall)).isFalse();
    }

    @Test
    void wrappedStepArgumentsAreComparedCorrectly() {
        // 测试 input_json 包装结构（含 toolCallId 和 arguments）
        ObjectNode args = json.createObjectNode().put("overdueOnly", false).put("limit", 5);
        ObjectNode result = json.createObjectNode().put("count", 3);

        // 模拟包装结构
        ObjectNode wrappedInput = json.createObjectNode();
        wrappedInput.put("toolCallId", "tc-1");
        wrappedInput.set("arguments", args.deepCopy());

        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("task.search", args.deepCopy(), "");

        List<AgentStepView> steps = List.of(
                toolStep("task.search", wrappedInput.deepCopy().toString(), result.deepCopy().toString()),
                toolStep("task.search", wrappedInput.deepCopy().toString(), result.deepCopy().toString()));

        // 即使 input_json 是包装结构，也应该比较 arguments 部分
        assertThat(guard.hasNoProgress(steps, nextCall)).isTrue();
    }

    @Test
    void differentResultsAreNotFalsePositiveLoop() {
        // 相同工具、相同参数但不同结果 = 有进展
        ObjectNode args = json.createObjectNode().put("overdueOnly", false).put("limit", 5);
        ObjectNode result1 = json.createObjectNode().put("count", 3);
        ObjectNode result2 = json.createObjectNode().put("count", 5);

        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("task.search", args.deepCopy(), "");

        List<AgentStepView> steps = List.of(
                toolStep("task.search", args.deepCopy().toString(), result1.deepCopy().toString()),
                toolStep("task.search", args.deepCopy().toString(), result2.deepCopy().toString()));

        assertThat(guard.hasNoProgress(steps, nextCall)).isFalse();
    }

    @Test
    void crossTickScenarioWorksCorrectly() {
        // 跨 Tick 场景：三个相同步骤 = 无进展
        ObjectNode args = json.createObjectNode().put("overdueOnly", false).put("limit", 5);
        ObjectNode result = json.createObjectNode().put("count", 3);

        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("task.search", args.deepCopy(), "");

        List<AgentStepView> steps = List.of(
                toolStep("task.search", args.deepCopy().toString(), result.deepCopy().toString()),
                toolStep("task.search", args.deepCopy().toString(), result.deepCopy().toString()),
                toolStep("task.search", args.deepCopy().toString(), result.deepCopy().toString()));

        // 即使有三个步骤，只要最后两个相同且下一步也相同 = 无进展
        assertThat(guard.hasNoProgress(steps, nextCall)).isTrue();
    }

    private AgentStepView toolStep(String toolName, String inputJson, String outputJson) {
        try {
            return new AgentStepView(
                    UUID.randomUUID(),
                    1,
                    AgentStepType.TOOL_CALL_COMPLETED,
                    toolName,
                    json.readTree(inputJson),
                    json.readTree(outputJson),
                    "TOOL_SUCCESS",
                    null, null, false, null, null,
                    OffsetDateTime.now());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
