package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.infrastructure.tool.PlanningReadOnlyTool;
import com.shitulelv.aicollab.planning.infrastructure.tool.PlanningToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlanningToolLoop 有界小循环单测（设计文档 §5.2）：
 * 工具调用上限、结果注入与清洗、畸形调用容错、预算用尽强制收敛。
 */
class PlanningToolLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 恒定返回同一工具调用的桩工具。 */
    private static PlanningReadOnlyTool stubTool(String name, String resultJson) {
        return new PlanningReadOnlyTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "测试工具 " + name; }
            @Override public JsonNode execute(JsonNode arguments, UUID projectId, UUID userId) {
                try {
                    return JSON.readTree(resultJson);
                } catch (Exception impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        };
    }

    /** 按调用次序依次返回内容的模型桩（内容用尽时重复最后一个）。 */
    private static TaskPlanModelClient modelReturning(String... contents) {
        TaskPlanModelClient model = mock(TaskPlanModelClient.class);
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            int i = index.getAndIncrement();
            String content = i < contents.length ? contents[i] : contents[contents.length - 1];
            return new GenerationResult(content, "p", "m", 1, 1, 1);
        }).when(model).generate(anyString(), anyString(), anyString(), any(), any(), any(), any(),
                any(JsonNode.class));
        return model;
    }

    @Test
    void toolCallIsExecutedAndSanitizedResultInjectedIntoNextPrompt() throws Exception {
        // 工具结果中的敏感字段（token）必须被清洗为 [REDACTED] 后才进入下一轮 prompt
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                stubTool("get_project_milestones", "{\"milestones\":[\"m1\"],\"token\":\"super-secret\"}")));
        PlanningToolLoop loop = new PlanningToolLoop(registry, JSON);
        String finalJson = "{\"issues\":[]}";
        TaskPlanModelClient model = modelReturning(
                "<TOOL_CALL>{\"tool\":\"get_project_milestones\",\"arguments\":{}}</TOOL_CALL>",
                finalJson);

        List<String> prompts = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            prompts.add(invocation.getArgument(1));
            return new GenerationResult(
                    prompts.size() == 1
                            ? "<TOOL_CALL>{\"tool\":\"get_project_milestones\",\"arguments\":{}}</TOOL_CALL>"
                            : finalJson,
                    "p", "m", 1, 1, 1);
        }).when(model).generate(anyString(), anyString(), anyString(), any(), any(), any(), any(),
                any(JsonNode.class));

        PlanningToolLoop.Outcome outcome = loop.run(model, "sys", "prompt", "TASK_PLAN_SKELETON",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                JSON.createObjectNode(), new PlanningToolLoop.ToolBudget(),
                Set.of("get_project_milestones"), null);

        assertThat(outcome.result().content()).isEqualTo(finalJson);
        assertThat(outcome.invocations()).hasSize(1);
        assertThat(outcome.invocations().getFirst().ok()).isTrue();
        assertThat(outcome.invocations().getFirst().result().get("token").asText()).isEqualTo("[REDACTED]");
        // 第二轮 prompt 注入了清洗后的结果；原始敏感值不得出现；
        // 包装标签是受信结构不转义，转义边界由 payload 内容自行体现
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("[REDACTED]");
        assertThat(prompts.get(1)).doesNotContain("super-secret");
        assertThat(prompts.get(1)).contains("<TOOL_RESULT name=\"get_project_milestones\" seq=\"0\" ok=\"true\">");
    }

    @Test
    void loopStopsAtToolCallBudget() {
        // 模型一直请求工具：预算 6 次用尽后强制收敛，最多 6 + 2 次模型调用
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                stubTool("get_project_milestones", "{\"milestones\":[]}")));
        PlanningToolLoop loop = new PlanningToolLoop(registry, JSON);
        String endlessCall = "<TOOL_CALL>{\"tool\":\"get_project_milestones\",\"arguments\":{}}</TOOL_CALL>";
        TaskPlanModelClient model = mock(TaskPlanModelClient.class);
        when(model.generate(anyString(), anyString(), anyString(), any(), any(), any(), any(),
                any(JsonNode.class)))
                .thenReturn(new GenerationResult(endlessCall, "p", "m", 1, 1, 1));

        PlanningToolLoop.Outcome outcome = loop.run(model, "sys", "prompt", "TASK_PLAN_DETAIL",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                JSON.createObjectNode(), new PlanningToolLoop.ToolBudget(),
                Set.of("get_project_milestones"), null);

        assertThat(outcome.invocations()).hasSize(PlanningToolLoop.MAX_TOOL_CALLS_PER_ATTEMPT);
        verify(model, times(PlanningToolLoop.MAX_TOOL_CALLS_PER_ATTEMPT + 2))
                .generate(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(JsonNode.class));
    }

    @Test
    void malformedToolCallIsRecordedAndLoopContinues() {
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                stubTool("get_project_milestones", "{\"milestones\":[]}")));
        PlanningToolLoop loop = new PlanningToolLoop(registry, JSON);
        TaskPlanModelClient model = modelReturning(
                "<TOOL_CALL>not-json</TOOL_CALL>",
                "{\"issues\":[]}");

        PlanningToolLoop.Outcome outcome = loop.run(model, "sys", "prompt", "TASK_PLAN_DETAIL",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                JSON.createObjectNode(), new PlanningToolLoop.ToolBudget(),
                Set.of("get_project_milestones"), null);

        assertThat(outcome.result().content()).isEqualTo("{\"issues\":[]}");
        assertThat(outcome.invocations()).hasSize(1);
        assertThat(outcome.invocations().getFirst().ok()).isFalse();
        assertThat(outcome.invocations().getFirst().error()).isEqualTo("MALFORMED_TOOL_CALL");
    }

    @Test
    void toolOutsideAllowlistIsRejected() {
        // 不在白名单的工具请求一律拒绝（即使注册表里存在）
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(
                stubTool("get_member_workload", "{\"members\":[]}")));
        PlanningToolLoop loop = new PlanningToolLoop(registry, JSON);
        TaskPlanModelClient model = modelReturning(
                "<TOOL_CALL>{\"tool\":\"get_member_workload\",\"arguments\":{}}</TOOL_CALL>",
                "{\"issues\":[]}");

        PlanningToolLoop.Outcome outcome = loop.run(model, "sys", "prompt", "TASK_PLAN_SKELETON",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                JSON.createObjectNode(), new PlanningToolLoop.ToolBudget(),
                Set.of("get_project_milestones"), null);

        assertThat(outcome.invocations()).hasSize(1);
        assertThat(outcome.invocations().getFirst().ok()).isFalse();
        assertThat(outcome.invocations().getFirst().error()).isEqualTo("TOOL_NOT_ALLOWED");
    }

    @Test
    void extractToolCallFindsTagContent() {
        assertThat(PlanningToolLoop.extractToolCall("plain json")).isNull();
        assertThat(PlanningToolLoop.extractToolCall("<TOOL_CALL>{\"a\":1}</TOOL_CALL>")).isEqualTo("{\"a\":1}");
        assertThat(PlanningToolLoop.extractToolCall("<TOOL_CALL>unterminated")).isNull();
    }

    @Test
    void toolBudgetIsBounded() {
        PlanningToolLoop.ToolBudget budget = new PlanningToolLoop.ToolBudget();
        for (int i = 0; i < PlanningToolLoop.MAX_TOOL_CALLS_PER_ATTEMPT; i++) {
            assertThat(budget.tryConsume()).isTrue();
        }
        assertThat(budget.tryConsume()).isFalse();
    }
}
