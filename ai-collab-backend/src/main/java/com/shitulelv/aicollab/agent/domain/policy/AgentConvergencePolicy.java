package com.shitulelv.aicollab.agent.domain.policy;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class AgentConvergencePolicy {
    private static final int FINAL_MODEL_AND_ANSWER_STEPS = 2;

    public Decision decide(
            AgentRunView run, AgentRuntimeLimits limits, List<AgentStepView> steps) {
        List<AgentStepView> persisted = steps == null ? List.of() : steps;
        int modelTurns = (int) persisted.stream()
                .filter(step -> step.type() == AgentStepType.MODEL_TURN)
                .count();
        int completedToolCalls = (int) persisted.stream()
                .filter(step -> step.type() == AgentStepType.TOOL_CALL_COMPLETED)
                .count();
        int successfulToolCalls = (int) persisted.stream()
                .filter(step -> step.type() == AgentStepType.TOOL_CALL_COMPLETED)
                .filter(step -> "TOOL_SUCCESS".equals(step.reason()))
                .count();

        int remainingSteps = run.maxSteps() - run.stepsUsed();
        int effectiveMaxToolCalls = Math.min(run.maxToolCalls(), limits.maxToolCalls());
        boolean cannotFinish = remainingSteps < FINAL_MODEL_AND_ANSWER_STEPS
                || modelTurns >= limits.maxModelTurns();
        if (cannotFinish) {
            return new Decision(Mode.EXHAUSTED, modelTurns, completedToolCalls, successfulToolCalls);
        }

        boolean finalBoundary = remainingSteps == FINAL_MODEL_AND_ANSWER_STEPS
                || modelTurns == limits.maxModelTurns() - 1
                || run.toolCallsUsed() >= effectiveMaxToolCalls;
        Mode mode = finalBoundary
                ? successfulToolCalls > 0 ? Mode.FINALIZE : Mode.EXHAUSTED
                : Mode.CONTINUE;
        return new Decision(mode, modelTurns, completedToolCalls, successfulToolCalls);
    }

    public void validateToolBatch(
            AgentRunView run, AgentRuntimeLimits limits, int batchSize) {
        if (batchSize < 0) {
            throw new IllegalArgumentException("工具调用数量不能为负数");
        }
        if (batchSize > limits.maxToolCallsPerTurn()) {
            throw new IllegalArgumentException("单轮工具调用超过预算");
        }
        int effectiveMaxToolCalls = Math.min(run.maxToolCalls(), limits.maxToolCalls());
        if (run.toolCallsUsed() + batchSize > effectiveMaxToolCalls) {
            throw new IllegalArgumentException("工具总预算不足");
        }
    }

    public enum Mode {
        CONTINUE,
        FINALIZE,
        EXHAUSTED
    }

    public record Decision(
            Mode mode,
            int modelTurns,
            int completedToolCalls,
            int successfulToolCalls) {
    }
}
