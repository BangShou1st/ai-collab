package com.shitulelv.aicollab.agent.domain.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.shitulelv.aicollab.agent.domain.policy.AgentConvergencePolicy.Mode.CONTINUE;
import static com.shitulelv.aicollab.agent.domain.policy.AgentConvergencePolicy.Mode.EXHAUSTED;
import static com.shitulelv.aicollab.agent.domain.policy.AgentConvergencePolicy.Mode.FINALIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentConvergencePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentConvergencePolicy policy = new AgentConvergencePolicy();

    @Test
    void reservesLastTwoStepsForFinalAnswerWhenSuccessfulToolEvidenceExists() {
        AgentConvergencePolicy.Decision decision = policy.decide(
                run(10, 12, 5, 8), limits(8, 4), steps(5, true));

        assertThat(decision.mode()).isEqualTo(FINALIZE);
        assertThat(decision.modelTurns()).isEqualTo(5);
        assertThat(decision.completedToolCalls()).isEqualTo(5);
        assertThat(decision.successfulToolCalls()).isEqualTo(5);
    }

    @Test
    void doesNotFabricateFinalAnswerAtBoundaryWithoutSuccessfulToolEvidence() {
        AgentConvergencePolicy.Decision decision = policy.decide(
                run(10, 12, 0, 8), limits(8, 4), List.of());

        assertThat(decision.mode()).isEqualTo(EXHAUSTED);
    }

    @Test
    void finalizesBeforeLastAllowedModelTurn() {
        AgentConvergencePolicy.Decision decision = policy.decide(
                run(8, 16, 4, 12), limits(5, 4), steps(4, true));

        assertThat(decision.mode()).isEqualTo(FINALIZE);
    }

    @Test
    void continuesWhenBudgetsHaveRoom() {
        AgentConvergencePolicy.Decision decision = policy.decide(
                run(4, 12, 2, 8), limits(8, 4), steps(2, true));

        assertThat(decision.mode()).isEqualTo(CONTINUE);
    }

    @Test
    void rejectsWholeBatchThatExceedsRemainingToolBudget() {
        assertThatThrownBy(() -> policy.validateToolBatch(
                run(4, 12, 7, 8), limits(8, 4), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具总预算");
    }

    @Test
    void rejectsWholeBatchThatExceedsPerTurnLimit() {
        assertThatThrownBy(() -> policy.validateToolBatch(
                run(4, 12, 1, 8), limits(8, 2), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单轮工具");
    }

    private AgentRuntimeLimits limits(int maxModelTurns, int maxToolCallsPerTurn) {
        return new AgentRuntimeLimits(
                16, maxModelTurns, 12, maxToolCallsPerTurn,
                Duration.ofMinutes(3), Duration.ofSeconds(10), Duration.ofSeconds(15),
                32 * 1024, 100_000, 32_000);
    }

    private AgentRunView run(int stepsUsed, int maxSteps, int toolCallsUsed, int maxToolCalls) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AgentRunView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "SUPERVISOR", 0, "读取仓库根目录", AgentRunStatus.RUNNING,
                maxSteps, maxToolCalls, 3, 50_000, 20_000,
                stepsUsed, toolCallsUsed, 0, 1_000, 100, false,
                false, false, 0, null, null, null, "PROJECT_RESEARCH", 1, now, now);
    }

    private List<AgentStepView> steps(int completedCalls, boolean successful) {
        List<AgentStepView> result = new ArrayList<>();
        int sequence = 1;
        for (int i = 0; i < completedCalls; i++) {
            result.add(step(sequence++, AgentStepType.MODEL_TURN, null, null));
            result.add(step(sequence++, AgentStepType.TOOL_CALL_COMPLETED,
                    "mcp.github.get_file_contents", successful ? "TOOL_SUCCESS" : "TOOL_ERROR"));
        }
        return result;
    }

    private AgentStepView step(int sequence, AgentStepType type, String toolName, String reason) {
        return new AgentStepView(
                UUID.randomUUID(), sequence, type, toolName,
                json.createObjectNode(), json.createObjectNode(), reason,
                null, null, false, null, null, OffsetDateTime.now());
    }
}
