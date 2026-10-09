package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 收敛判定：回答"是否还能发下一次请求"以及"下一批工具能否受理"。
 *
 * <h2>两种资源策略</h2>
 * <ul>
 *   <li><b>v1</b>：累计输入/输出上限参与收尾判定（沿用既有行为，恢复与暂停续跑不重解释）。</li>
 *   <li><b>v2</b>：累计 token <b>不参与</b>准入、收敛或停机——只统计真实/估算用量。
 *       防失控由有限决策轮次、推进步、工具次数、循环检测与活跃时长承担；
 *       单次请求的容量由模型真实窗口与单次输出配置约束（见 {@link AgentContextBudget}）。</li>
 * </ul>
 */
@Component
public final class AgentConvergencePolicy {
    private static final int FINAL_MODEL_AND_ANSWER_STEPS = 2;

    public Decision decide(
            AgentRunView run, AgentRuntimeLimits limits, List<AgentStepView> steps) {
        return decide(run, limits, steps, false);
    }

    /**
     * 收敛判定。{@code summarizableChildEvidence}：本运行已回收的子运行研究产出
     * （DELEGATION_COMPLETED 中的部分回答/结论）可作为无工具总结的依据——委派型父运行
     * 的工具消耗记在子运行名下，父运行自己的步骤里没有成功工具结果，只看
     * TOOL_CALL_COMPLETED 会把"额度用完但有子研究产出"误判成硬停；子产出仍以
     * 结构化身份进入最终回答，不计入父运行自己的工具成功次数。
     */
    public Decision decide(
            AgentRunView run, AgentRuntimeLimits limits, List<AgentStepView> steps,
            boolean summarizableChildEvidence) {
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
        // 到限判定只来自各运行自己的有限执行额度（步数/模型轮），不来自累计 token
        boolean cannotFinish = remainingSteps < FINAL_MODEL_AND_ANSWER_STEPS
                || modelTurns >= limits.maxModelTurns();
        if (cannotFinish) {
            return new Decision(Mode.EXHAUSTED, modelTurns, completedToolCalls, successfulToolCalls);
        }

        boolean finalBoundary = remainingSteps == FINAL_MODEL_AND_ANSWER_STEPS
                || modelTurns == limits.maxModelTurns() - 1
                || run.toolCallsUsed() >= effectiveMaxToolCalls;
        // v1 兼容：重复发送上下文同样消耗运行累计输入额度，为下一次最终请求预留可比空间。
        // v2 不执行这一段——累计输入只统计，不因"剩余累计输入不多"提前收尾
        // （那会把"窗口还能放下"误判成"额度不足"）。
        if (AgentResourcePolicy.enforcesCumulativeTokenLimits(run.contextPolicyVersion())) {
            Integer lastInput = persisted.stream()
                    .filter(step -> step.type() == AgentStepType.MODEL_TURN && step.promptTokens() != null)
                    .reduce((previous, current) -> current).map(AgentStepView::promptTokens).orElse(null);
            if (lastInput != null && lastInput > 0 && successfulToolCalls > 0) {
                long remainingInput = AgentResourcePolicy.remaining(
                        AgentResourcePolicy.effectiveInputCap(
                                run.contextPolicyVersion(), run.maxInputTokens(), limits.maxInputTokens()),
                        run.inputTokensUsed());
                finalBoundary |= remainingInput <= 2L * lastInput;
            }
        }
        Mode mode = finalBoundary
                ? successfulToolCalls > 0 || summarizableChildEvidence ? Mode.FINALIZE : Mode.EXHAUSTED
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
     * <p>工具次数是<b>本运行自己的</b>有限执行额度（v2 下子运行不从父剩余切分，
     * 父也不因回收子用量而扣减自身额度）。</p>
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

    /**
     * 估算下一次证据请求与一次最终请求的开销，判定是否应停止追加工具、转入无工具收尾。
     *
     * <p>v1：累计输入剩余量参与（沿用既有公式）。v2：累计 token <b>不参与</b>，
     * 只看本运行自身的推进步是否还容得下发一次请求加一次收尾——不再按"剩余累计输入
     * 不足两次请求"提前强制收尾。</p>
     */
    public boolean needsFinalRequest(AgentRunView run, AgentRuntimeLimits limits,
            List<AgentStepView> steps, int nextInput, int outputReserve) {
        boolean cumulative = AgentResourcePolicy.enforcesCumulativeTokenLimits(run.contextPolicyVersion());
        if (!cumulative) {
            // v2：收尾可负担性只由推进步与模型轮次决定（二者是自身有限执行额度）
            return run.maxSteps() - run.stepsUsed() < FINAL_MODEL_AND_ANSWER_STEPS;
        }
        int lastInput = (steps == null ? List.<AgentStepView>of() : steps).stream()
                .filter(s -> s.type() == AgentStepType.MODEL_TURN && s.promptTokens() != null)
                .reduce((a, b) -> b).map(AgentStepView::promptTokens).orElse(0);
        long requestCost = Math.max(nextInput, lastInput);
        long remainingInput = AgentResourcePolicy.remaining(
                AgentResourcePolicy.effectiveInputCap(
                        run.contextPolicyVersion(), run.maxInputTokens(), limits.maxInputTokens()),
                run.inputTokensUsed());
        long remainingOutput = AgentResourcePolicy.remaining(
                AgentResourcePolicy.effectiveOutputCap(
                        run.contextPolicyVersion(), run.maxOutputTokens(), limits.maxOutputTokens()),
                run.outputTokensUsed());
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
