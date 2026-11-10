package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.infrastructure.tool.DocumentResearchDelegateAgentTool;
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
        // 验证 extractArguments 能正确提取包装结构中的 arguments 部分
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

        // 即使 input_json 是包装结构，extractArguments 也能正确提取 arguments 并检测循环
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

    @Test
    void alternatingCycleDetected() {
        // 交替循环：A → B → A → B（下一步是 A，窗口内 A 出现 3 次）
        ObjectNode result = json.createObjectNode().put("count", 1);

        AgentDecision.CallTool nextCallA = new AgentDecision.CallTool("task.search",
                json.createObjectNode().put("limit", 5), "");

        List<AgentStepView> steps = List.of(
                toolStep("task.search", "{\"limit\":5}", result.deepCopy().toString()),
                toolStep("milestone.list", "{}", result.deepCopy().toString()),
                toolStep("task.search", "{\"limit\":5}", result.deepCopy().toString()),
                toolStep("milestone.list", "{}", result.deepCopy().toString()));

        // 窗口：[task.search, milestone.list, task.search, milestone.list, task.search(next)]
        // task.search 出现 3 次 → 无进展
        assertThat(guard.hasNoProgress(steps, nextCallA)).isTrue();
    }

    @Test
    void threeToolCycleDetected() {
        // 三工具循环：A → B → C → A（下一步是 B，窗口内 B 出现 3 次）
        ObjectNode result = json.createObjectNode().put("data", "x");

        AgentDecision.CallTool nextCallB = new AgentDecision.CallTool("milestone.list",
                json.createObjectNode(), "");

        List<AgentStepView> steps = List.of(
                toolStep("task.search", "{\"limit\":5}", result.deepCopy().toString()),
                toolStep("milestone.list", "{}", result.deepCopy().toString()),
                toolStep("get_project_overview", "{}", result.deepCopy().toString()),
                toolStep("task.search", "{\"limit\":5}", result.deepCopy().toString()),
                toolStep("milestone.list", "{}", result.deepCopy().toString()));

        // 窗口：[task.search, milestone.list, overview, task.search, milestone.list, milestone.list(next)]
        // milestone.list 出现 3 次 → 无进展
        assertThat(guard.hasNoProgress(steps, nextCallB)).isTrue();
    }

    @Test
    void diverseToolsAreNotFalsePositive() {
        // 多个不同工具 = 有进展
        ObjectNode result = json.createObjectNode().put("data", "x");

        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("get_project_overview",
                json.createObjectNode(), "");

        List<AgentStepView> steps = List.of(
                toolStep("task.search", "{\"limit\":5}", result.deepCopy().toString()),
                toolStep("milestone.list", "{}", result.deepCopy().toString()),
                toolStep("get_project_overview", "{}", result.deepCopy().toString()));

        // 每个签名只出现 1 次 + next 1 次 = 2 次 < 3 阈值
        assertThat(guard.hasNoProgress(steps, nextCall)).isFalse();
    }

    // ===== 委派工具的 objective 哈希签名（设计文档 §3.2 预测性拦截扩展） =====

    private static final String DELEGATE_TOOL = DocumentResearchDelegateAgentTool.NAME;

    private ObjectNode delegateArgs(String objective) {
        ObjectNode args = json.createObjectNode();
        args.put("objective", objective);
        return args;
    }

    /** 委派工具签名忽略参数表面差异：三次 objective 变体（大小写/标点/空白）判无进展。 */
    @Test
    void delegationObjectiveVariantsHitWindowThreshold() {
        List<AgentStepView> steps = List.of(
                toolStep(DELEGATE_TOOL,
                        delegateArgs("Research the acceptance criteria").toString(),
                        "{\"status\":\"REJECTED\",\"errorCode\":\"AGENT_DELEGATION_BUDGET_INSUFFICIENT\"}"),
                toolStep(DELEGATE_TOOL,
                        delegateArgs("RESEARCH, the acceptance criteria!!").toString(),
                        "{\"status\":\"REJECTED\",\"errorCode\":\"AGENT_DELEGATION_DUPLICATE_OBJECTIVE\"}"),
                toolStep(DELEGATE_TOOL,
                        delegateArgs("  research   the  acceptance criteria  ").toString(),
                        "{\"status\":\"REJECTED\",\"errorCode\":\"AGENT_DELEGATION_CHILDREN_EXHAUSTED\"}"));
        AgentDecision.CallTool nextCall = new AgentDecision.CallTool(DELEGATE_TOOL,
                delegateArgs("research the acceptance criteria"), "");
        // 签名只看 toolName|objectiveHash：三个变体同键，窗口内 ≥3 → 无进展
        assertThat(guard.hasNoProgress(steps, nextCall)).isTrue();
    }

    /** 不同 objective 的委派不误判：签名不同，窗口内各自只出现 1 次。 */
    @Test
    void delegationWithDifferentObjectivesIsNotNoProgress() {
        List<AgentStepView> steps = List.of(
                toolStep(DELEGATE_TOOL,
                        delegateArgs("Research the acceptance criteria").toString(),
                        "{\"status\":\"REJECTED\"}"),
                toolStep(DELEGATE_TOOL,
                        delegateArgs("Research the dependency graph").toString(),
                        "{\"status\":\"REJECTED\"}"),
                toolStep(DELEGATE_TOOL,
                        delegateArgs("Research the milestone plan").toString(),
                        "{\"status\":\"REJECTED\"}"));
        AgentDecision.CallTool nextCall = new AgentDecision.CallTool(DELEGATE_TOOL,
                delegateArgs("Research the risk register"), "");
        assertThat(guard.hasNoProgress(steps, nextCall)).isFalse();
    }

    /** 委派签名不含 output：同 objective 同参数下不同结果仍判重（与非委派工具相反）。 */
    @Test
    void delegationSignatureIgnoresOutputDifferences() {
        String args = delegateArgs("Research the acceptance criteria").toString();
        List<AgentStepView> steps = List.of(
                toolStep(DELEGATE_TOOL, args, "{\"status\":\"REJECTED\",\"attempt\":1}"),
                toolStep(DELEGATE_TOOL, args, "{\"status\":\"REJECTED\",\"attempt\":2}"));
        AgentDecision.CallTool nextCall = new AgentDecision.CallTool(DELEGATE_TOOL,
                delegateArgs("Research the acceptance criteria"), "");
        // 窗口内同签名 2 次 + 预测的下一步 1 次 = 3 → 无进展
        assertThat(guard.hasNoProgress(steps, nextCall)).isTrue();
    }

    /** 委派工具的包装 input 结构（toolCallId + arguments）同样提取 objective 判重。 */
    @Test
    void delegationWrappedArgumentsAreNormalizedForSignature() {
        ObjectNode wrapped = json.createObjectNode();
        wrapped.put("toolCallId", "tc-1");
        wrapped.set("arguments", delegateArgs("Research the acceptance criteria"));

        List<AgentStepView> steps = List.of(
                toolStep(DELEGATE_TOOL, wrapped.deepCopy().toString(), "{\"status\":\"REJECTED\"}"),
                toolStep(DELEGATE_TOOL,
                        delegateArgs("research-the-acceptance-criteria").toString(),
                        "{\"status\":\"REJECTED\"}"));
        AgentDecision.CallTool nextCall = new AgentDecision.CallTool(DELEGATE_TOOL,
                delegateArgs("RESEARCH THE ACCEPTANCE CRITERIA"), "");
        // 同 objective 哈希出现 2 次 + 预测下一步 1 次 = 3 → 无进展
        assertThat(guard.hasNoProgress(steps, nextCall)).isTrue();
    }

    /** 非委派工具的签名保持不变：相同 tool+args 但 output 不同仍视为有进展。 */
    @Test
    void nonDelegationToolsKeepOutputSensitiveSignature() {
        List<AgentStepView> steps = List.of(
                toolStep("task.search", "{\"limit\":5}", "{\"count\":3}"),
                toolStep("task.search", "{\"limit\":5}", "{\"count\":5}"));
        AgentDecision.CallTool nextCall = new AgentDecision.CallTool("task.search",
                json.createObjectNode().put("limit", 5), "");
        assertThat(guard.hasNoProgress(steps, nextCall)).isFalse();
        // 完全相同（同 args 同 output）仍然命中
        List<AgentStepView> identical = List.of(
                toolStep("task.search", "{\"limit\":5}", "{\"count\":3}"),
                toolStep("task.search", "{\"limit\":5}", "{\"count\":3}"));
        assertThat(guard.hasNoProgress(identical, nextCall)).isTrue();
    }

    /** 委派工具缺 objective 字段时不误伤：与其他委派步骤不共享签名。 */
    @Test
    void delegationWithoutObjectiveFieldDoesNotCollide() {
        List<AgentStepView> steps = List.of(
                toolStep(DELEGATE_TOOL, delegateArgs("Research the acceptance criteria").toString(),
                        "{\"status\":\"REJECTED\"}"),
                toolStep(DELEGATE_TOOL, "{}", "{\"status\":\"REJECTED\"}"));
        AgentDecision.CallTool nextCall = new AgentDecision.CallTool(DELEGATE_TOOL,
                delegateArgs("Research the dependency graph"), "");
        assertThat(guard.hasNoProgress(steps, nextCall)).isFalse();
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
