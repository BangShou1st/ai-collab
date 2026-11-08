package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 父运行综合注入块的分组渲染单测（设计文档 §3.1 部分失败 + §3.3 结构化回传）：
 * 成功子运行走 CHILD_RESEARCH（结构化 findings 分条）、失败子运行走
 * CHILD_RESEARCH_FAILURE（含失败原因）、旧格式 content 降级、空输入返回空串。
 */
class DelegatedResearchPromptRendererTest {
    private final ObjectMapper json = new ObjectMapper();

    private DelegatedResearchPromptRenderer.ChildResult result(String status, String jsonBody) {
        try {
            return new DelegatedResearchPromptRenderer.ChildResult(
                    UUID.randomUUID(), json.readTree(jsonBody));
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    @Test
    void emptyResultsRenderNothing() {
        assertThat(DelegatedResearchPromptRenderer.render(List.of())).isEmpty();
        assertThat(DelegatedResearchPromptRenderer.render(null)).isEmpty();
    }

    @Test
    void successChildUsesStructuredFindings() {
        String block = DelegatedResearchPromptRenderer.render(List.of(result("SUCCEEDED", """
                {"status":"SUCCEEDED","childRunId":"11111111-1111-1111-1111-111111111111",
                 "summary":"验收结论：全部测试通过",
                 "findings":["发现A（chunk-1）","发现B（chunk-2）"],
                 "openQuestions":["是否覆盖移动端？"],
                 "evidenceDigest":["工具原文摘录一"],
                 "content":"验收结论：全部测试通过"}""")));
        assertThat(block).contains("<CHILD_RESEARCH status=\"SUCCEEDED\"");
        assertThat(block).contains("结论：验收结论：全部测试通过");
        assertThat(block).contains("发现 1：发现A（chunk-1）");
        assertThat(block).contains("发现 2：发现B（chunk-2）");
        assertThat(block).contains("留给父运行的问题");
        assertThat(block).contains("关键证据摘录");
        assertThat(block).as("成功子运行不进入失败分组").doesNotContain("<CHILD_RESEARCH_FAILURE");
    }

    @Test
    void failureChildGroupedWithReasonAndPartialOutput() {
        String block = DelegatedResearchPromptRenderer.render(List.of(result("FAILED", """
                {"status":"FAILED","content":"MODEL_PROVIDER_ERROR"}""")));
        assertThat(block).contains("<CHILD_RESEARCH_FAILURE status=\"FAILED\"");
        assertThat(block).contains("子运行执行失败");
        assertThat(block).contains("MODEL_PROVIDER_ERROR");
        assertThat(block).contains("台账判重");
    }

    @Test
    void budgetExceededChildGetsDedicatedReason() {
        String block = DelegatedResearchPromptRenderer.render(List.of(result("BUDGET_EXCEEDED", """
                {"status":"BUDGET_EXCEEDED","content":"部分产出：已读到第三章"}""")));
        assertThat(block).contains("子运行预算耗尽，仅返回部分产出");
        assertThat(block).contains("部分产出：已读到第三章");
    }

    @Test
    void mixedSuccessAndFailureAreGrouped() {
        String block = DelegatedResearchPromptRenderer.render(List.of(
                result("SUCCEEDED", "{\"status\":\"SUCCEEDED\",\"content\":\"成功产出\"}"),
                result("CANCELED", "{\"status\":\"CANCELED\",\"content\":\"AGENT_RUN_CANCELED\"}")));
        int successAt = block.indexOf("<CHILD_RESEARCH status=\"SUCCEEDED\"");
        int failureAt = block.indexOf("<CHILD_RESEARCH_FAILURE status=\"CANCELED\"");
        assertThat(successAt).isGreaterThanOrEqualTo(0);
        assertThat(failureAt).isGreaterThan(successAt);
        assertThat(block).contains("子运行被取消");
        // 旧格式 content 降级仍然注入
        assertThat(block).contains("成功产出");
    }
}
