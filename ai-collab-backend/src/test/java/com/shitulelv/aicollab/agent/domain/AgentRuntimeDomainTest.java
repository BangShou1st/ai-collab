package com.shitulelv.aicollab.agent.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentDecisionParser;
import com.shitulelv.aicollab.agent.domain.model.AgentBudget;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.policy.AgentStateMachine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRuntimeDomainTest {
    private final AgentDecisionParser parser = new AgentDecisionParser(new ObjectMapper());
    private final AgentStateMachine states = new AgentStateMachine();

    @Test
    void rejectsUnknownDecisionFields() {
        assertThatThrownBy(() -> parser.parse("""
                {"action":"final","answer":"完成","citations":[],"inferences":[],"extra":1}
                """, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void parsesCallToolWithoutAllowingProjectOverride() {
        AgentDecision decision = parser.parse("""
                {
                  "action":"call_tool",
                  "tool":"list_tasks",
                  "arguments":{"status":"TODO"},
                  "reason":"核对待办任务"
                }
                """, false);

        assertThat(decision).isInstanceOf(AgentDecision.CallTool.class);
        AgentDecision.CallTool call = (AgentDecision.CallTool) decision;
        assertThat(call.tool()).isEqualTo("list_tasks");
        assertThat(call.arguments().path("status").asText()).isEqualTo("TODO");

        assertThatThrownBy(() -> parser.parse("""
                {
                  "action":"call_tool",
                  "tool":"list_tasks",
                  "arguments":{"projectId":"3dd7c733-dd63-42ef-93fc-41fc73271db5"},
                  "reason":"尝试覆盖项目"
                }
                """, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    void permitsOnlyOneCorrectionAttempt() {
        assertThatThrownBy(() -> parser.parse("not-json", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("可纠正");
        assertThatThrownBy(() -> parser.parse("not-json", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可纠正");
    }

    @Test
    void budgetStopsAtEveryHardLimit() {
        AgentBudget defaults = AgentBudget.defaults();

        assertThat(defaults.maxSteps()).isEqualTo(12);
        assertThat(defaults.maxToolCalls()).isEqualTo(8);
        assertThat(defaults.maxChildren()).isEqualTo(3);
        assertThatThrownBy(() -> defaults.withUsage(12, 0, 0, 0, 0, false).debitStep())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> defaults.withUsage(0, 8, 0, 0, 0, false).debitTool())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> defaults.withUsage(0, 0, 3, 0, 0, false).debitChild())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokenUsageKeepsEstimatedFlag() {
        AgentBudget budget = AgentBudget.defaults().debitTokens(120, 40, true);

        assertThat(budget.inputTokensUsed()).isEqualTo(120);
        assertThat(budget.outputTokensUsed()).isEqualTo(40);
        assertThat(budget.tokenUsageEstimated()).isTrue();
    }

    @Test
    void waitingApprovalResumesOnlyThroughQueue() {
        assertThatCode(() -> states.requireTransition(
                AgentRunStatus.WAITING_FOR_APPROVAL, AgentRunStatus.QUEUED))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> states.requireTransition(
                AgentRunStatus.WAITING_FOR_APPROVAL, AgentRunStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalRunCannotTransitionAgain() {
        assertThatThrownBy(() -> states.requireTransition(
                AgentRunStatus.SUCCEEDED, AgentRunStatus.QUEUED))
                .isInstanceOf(IllegalStateException.class);
    }
}
