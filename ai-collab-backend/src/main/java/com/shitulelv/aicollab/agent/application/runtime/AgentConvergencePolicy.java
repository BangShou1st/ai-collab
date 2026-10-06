package com.shitulelv.aicollab.agent.application.runtime;

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
        // Repeated context transmission consumes the run budget even with few tools.
        // Reserve a comparable final request before another evidence round; do not raise limits.
        Integer lastInput = persisted.stream()
                .filter(step -> step.type() == AgentStepType.MODEL_TURN && step.promptTokens() != null)
                .reduce((previous, current) -> current).map(AgentStepView::promptTokens).orElse(null);
        if (lastInput != null && lastInput > 0 && successfulToolCalls > 0) {
            long remainingInput = (long) Math.min(run.maxInputTokens(), limits.maxInputTokens()) - run.inputTokensUsed();
            finalBoundary |= remainingInput <= 2L * lastInput;
        }
        Mode mode = finalBoundary
                ? successfulToolCalls > 0 ? Mode.FINALIZE : Mode.EXHAUSTED
                : Mode.CONTINUE;
        return new Decision(mode, modelTurns, completedToolCalls, successfulToolCalls);
    }

    public void validateToolBatch(
            AgentRunView run, AgentRuntimeLimits limits, int batchSize) {
        validateToolBatch(run, limits, batchSize, 0);
    }

    /**
     * 批次准入：单轮数量仍按原批次校验；总额约束只计尚需执行的新增调用。
     * 恢复批次中已有持久化结果的调用按原 invocation 身份复用、不再占新增额度——
     * 每个已提交的工具结果在落库时已经推进 tool_calls_used，把整批再次计入
     * 会把"先完成一部分再退出"的合法批次误判为超限。真实超限仍拒绝。
     *
     * @param alreadyCompletedCalls 批次中已有持久化结果（或已绑定提案）的调用数；
     *                              新轮次传 0，语义与原版本一致
     */
    public void validateToolBatch(
            AgentRunView run, AgentRuntimeLimits limits, int batchSize, int alreadyCompletedCalls) {
        if (batchSize < 0 || alreadyCompletedCalls < 0) {
            throw new IllegalArgumentException("工具调用数量不能为负数");
        }
        if (batchSize > limits.maxToolCallsPerTurn()) {
            throw new IllegalArgumentException("单轮工具调用超过预算");
        }
        int effectiveMaxToolCalls = Math.min(run.maxToolCalls(), limits.maxToolCalls());
        if (run.toolCallsUsed() + batchSize - alreadyCompletedCalls > effectiveMaxToolCalls) {
            throw new IllegalArgumentException("工具总预算不足");
        }
    }

    /** Estimate the next evidence request and one final request before admitting more tools. */
    public boolean needsFinalRequest(AgentRunView run, AgentRuntimeLimits limits,
            List<AgentStepView> steps, int nextInput, int outputReserve) {
        int lastInput = (steps == null ? List.<AgentStepView>of() : steps).stream()
                .filter(s -> s.type() == AgentStepType.MODEL_TURN && s.promptTokens() != null)
                .reduce((a, b) -> b).map(AgentStepView::promptTokens).orElse(0);
        long requestCost = Math.max(nextInput, lastInput);
        long remainingInput = (long) Math.min(run.maxInputTokens(), limits.maxInputTokens()) - run.inputTokensUsed();
        long remainingOutput = (long) Math.min(run.maxOutputTokens(), limits.maxOutputTokens()) - run.outputTokensUsed();
        return remainingInput <= 2L * requestCost || remainingOutput <= outputReserve;
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
