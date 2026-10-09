package com.shitulelv.aicollab.agent.domain.model;

/**
 * document_research 委派的受理判定与子运行预算（纯函数，无数据库、无配置、可单测）。
 *
 * <p>把"委派能否受理"从"委派执行失败"里分离出来：<b>可预期的受理拒绝</b>
 * （委派次数耗尽、剩余执行额度容不下子运行最小研究与父综合收尾）是有确定原因的
 * 业务事实，调用方据此有边界地保留父运行；权限、安全、暂停、取消、租约与内部
 * 执行异常不属于本判定，仍走原失败边界。</p>
 *
 * <p>只按数值与类型判定，<b>不</b>根据异常消息文本判断原因；原因以
 * {@link RejectionReason} 枚举显式表达。</p>
 *
 * <h2>两种资源策略的切分语义</h2>
 * <ul>
 *   <li><b>v1</b>（{@code combined} 等既有事实）：沿用既有共享额度语义——子运行从
 *       父剩余额度切分（步数/工具/输入/输出），父综合预留 2 步，委派自身占 1 次工具调用。
 *       旧运行恢复与暂停续跑保持这一含义，不重解释。</li>
 *   <li><b>v2</b>：子运行使用<b>自己的独立执行额度</b>（{@link AgentResourcePolicy} 的
 *       子上限），<b>不从父剩余切分</b>；累计输入/输出不再切分（v2 本就没有累计上限）。
 *       父仍须能发起委派（占父 1 次工具）并完成自身综合收尾——这一检查不能变成
 *       "父还剩几次工具，子只能读几页"。</li>
 * </ul>
 */
public final class AgentDelegationAdmission {

    /** 父综合收尾预留推进步（收尾模型轮 + 回答落库）。 */
    public static final int PARENT_SYNTHESIS_RESERVE_STEPS = 2;
    /** 委派自身消耗的工具调用数（与普通工具同一配额语义）。 */
    public static final int DELEGATION_TOOL_CALL_COST = 1;

    public static final int MAX_CHILD_STEPS = 8;
    public static final int MAX_CHILD_TOOL_CALLS = 8;
    public static final int MAX_CHILD_INPUT_TOKENS = 30_000;
    public static final int MAX_CHILD_OUTPUT_TOKENS = 8_000;

    /** 子运行最小研究（1 轮）+ 收尾（收尾轮 + 落库）所需推进步。 */
    public static final int MIN_CHILD_STEPS = 3;
    public static final int MIN_CHILD_TOOL_CALLS = 1;
    public static final int MIN_CHILD_INPUT_TOKENS = 1_000;
    public static final int MIN_CHILD_OUTPUT_TOKENS = 1_000;

    /** 可预期的受理拒绝原因：显式类型，不依赖异常消息文本。 */
    public enum RejectionReason {
        /** 本次运行的委派次数已用完。 */
        CHILDREN_EXHAUSTED("AGENT_DELEGATION_CHILDREN_EXHAUSTED", "本次运行的委派次数已用完"),
        /** 父运行剩余执行额度连"发起委派 + 自身综合收尾"都容纳不了。 */
        INSUFFICIENT_BUDGET("AGENT_DELEGATION_BUDGET_INSUFFICIENT",
                "父运行剩余预算无法容纳子运行最小研究与收尾，已拒绝受理");

        private final String code;
        private final String message;

        RejectionReason(String code, String message) {
            this.code = code;
            this.message = message;
        }

        /** 持久化到工具结果 output_json.error 的稳定错误码（不随语言改写）。 */
        public String code() { return code; }

        /** 面向用户的确定性中文原因。 */
        public String message() { return message; }
    }

    /**
     * 参与受理判定的运行事实快照（由调用方从运行视图取值，避免 domain 反向依赖 application）。
     *
     * @param contextPolicyVersion 资源策略版本（1=累计额度共享切分，2=父子独立执行额度）
     * @param combined             仅 v1 有意义：COMBINED 旧语义下委派受理另占一个推进步
     */
    public record Facts(
            int maxSteps, int stepsUsed,
            int maxToolCalls, int toolCallsUsed,
            Integer maxInputTokens, int inputTokensUsed,
            Integer maxOutputTokens, int outputTokensUsed,
            int childrenUsed, int maxChildren,
            boolean combined,
            int contextPolicyVersion) {

        /** v1 兼容构造（旧调用点/既有测试）：缺省策略版本为 v1。 */
        public Facts(int maxSteps, int stepsUsed, int maxToolCalls, int toolCallsUsed,
                Integer maxInputTokens, int inputTokensUsed, Integer maxOutputTokens, int outputTokensUsed,
                int childrenUsed, int maxChildren, boolean combined) {
            this(maxSteps, stepsUsed, maxToolCalls, toolCallsUsed, maxInputTokens, inputTokensUsed,
                    maxOutputTokens, outputTokensUsed, childrenUsed, maxChildren, combined,
                    AgentResourcePolicy.V1);
        }

        public boolean independentChildBudget() {
            return AgentResourcePolicy.normalize(contextPolicyVersion) == AgentResourcePolicy.V2;
        }
    }

    /** 子运行可用预算。v2 下是独立执行额度（输入/输出为 null 表示不设累计上限）。 */
    public record ChildBudget(int steps, int toolCalls, Integer inputTokens, Integer outputTokens) {
    }

    private AgentDelegationAdmission() {}

    /**
     * 解析子运行可用预算。
     *
     * <p>v1：从父剩余额度切出（委派自身占一次工具调用，父综合收尾预留 2 个推进步，
     * COMBINED 旧语义下另占一个推进步；输入/输出按父剩余等比对半并封顶）。</p>
     *
     * <p>v2：使用子运行自己的独立有限执行额度，不从父剩余切分；累计输入/输出为 {@code null}
     * （只统计、不限额），因此这里不产生任何"子只能读多少"的 token 上限。</p>
     */
    public static ChildBudget split(Facts facts) {
        if (facts.independentChildBudget()) {
            return new ChildBudget(
                    AgentResourcePolicy.V2_CHILD_MAX_STEPS,
                    AgentResourcePolicy.V2_CHILD_MAX_TOOL_CALLS,
                    null,
                    null);
        }
        int delegationStepCost = facts.combined() ? 1 : 0;
        int steps = Math.min(MAX_CHILD_STEPS, Math.max(0,
                facts.maxSteps() - facts.stepsUsed() - delegationStepCost - PARENT_SYNTHESIS_RESERVE_STEPS));
        int toolCalls = Math.min(MAX_CHILD_TOOL_CALLS, Math.max(0,
                facts.maxToolCalls() - facts.toolCallsUsed() - DELEGATION_TOOL_CALL_COST));
        Integer parentInputCap = AgentResourcePolicy.effectiveInputCap(
                facts.contextPolicyVersion(), facts.maxInputTokens(), null);
        Integer parentOutputCap = AgentResourcePolicy.effectiveOutputCap(
                facts.contextPolicyVersion(), facts.maxOutputTokens(), null);
        Integer inputTokens = parentInputCap == null ? null : Math.min(MAX_CHILD_INPUT_TOKENS, Math.max(0,
                (parentInputCap - facts.inputTokensUsed()) / 2));
        Integer outputTokens = parentOutputCap == null ? null : Math.min(MAX_CHILD_OUTPUT_TOKENS, Math.max(0,
                (parentOutputCap - facts.outputTokensUsed()) / 2));
        return new ChildBudget(steps, toolCalls, inputTokens, outputTokens);
    }

    /**
     * 可预期的受理拒绝原因；{@code null} 表示可以受理。
     * 判定只看运行事实，同一运行事实下结论稳定（恢复重放得到相同结果）。
     *
     * <p><b>F6 修复：</b>调用方（工具可见性）判定"下一轮请求是否还能委派"时，
     * 必须计入本轮即将发生的已知推进成本（模型轮落库占 1 个推进步），
     * 否则会暴露一个"模型轮落库后必然被拒"的委派工具。受理事务自身在落库后判定，
     * 此时该成本已经发生，因此同一个 {@code Facts} 无需重复扣除——
     * 成本由调用方按"本请求是否尚未落库"决定是否预先计入（见 {@code upcomingModelStep}）。</p>
     */
    public static RejectionReason reject(Facts facts) {
        if (facts.childrenUsed() >= facts.maxChildren()) return RejectionReason.CHILDREN_EXHAUSTED;
        // 父自身必须仍能发起委派（1 次工具）并完成综合收尾（2 个推进步）：
        // 这是父自身执行额度的检查，不是"子能用多少"的切分。
        int delegationStepCost = facts.combined() ? 1 : 0;
        int parentStepsAvailable = facts.maxSteps() - facts.stepsUsed() - delegationStepCost;
        if (parentStepsAvailable < PARENT_SYNTHESIS_RESERVE_STEPS) {
            return RejectionReason.INSUFFICIENT_BUDGET;
        }
        if (facts.toolCallsUsed() + DELEGATION_TOOL_CALL_COST > facts.maxToolCalls()) {
            return RejectionReason.INSUFFICIENT_BUDGET;
        }
        if (facts.independentChildBudget()) {
            // v2：子运行用自身独立额度，不再要求"父剩余能切出子最小额度"。
            // 只需保证子自身的最小执行额度是自洽的（配置层常量，防御性检查）。
            if (AgentResourcePolicy.V2_CHILD_MAX_STEPS < MIN_CHILD_STEPS
                    || AgentResourcePolicy.V2_CHILD_MAX_TOOL_CALLS < MIN_CHILD_TOOL_CALLS) {
                return RejectionReason.INSUFFICIENT_BUDGET;
            }
            return null;
        }
        ChildBudget budget = split(facts);
        if (budget.steps() < MIN_CHILD_STEPS || budget.toolCalls() < MIN_CHILD_TOOL_CALLS
                || budget.inputTokens() == null || budget.inputTokens() < MIN_CHILD_INPUT_TOKENS
                || budget.outputTokens() == null || budget.outputTokens() < MIN_CHILD_OUTPUT_TOKENS) {
            return RejectionReason.INSUFFICIENT_BUDGET;
        }
        return null;
    }

    /** 当前运行是否还能受理新的委派（供工具可见性收窄使用）。 */
    public static boolean admitsDelegation(Facts facts) {
        return reject(facts) == null;
    }

    /**
     * 工具可见性判定的运行事实：在本轮模型请求<b>发出前</b>评估，此时本轮模型轮尚未落库，
     * 但一旦模型返回并落库就会占用一个推进步（两种语义下模型轮都计推进步）。
     * 因此可见性判定必须<b>预先计入</b>这一已知成本，否则会暴露一个必然被拒的委派工具
     * （F6 反例：剩余 5 步的 SEPARATED 运行先按"子预算 3 步"暴露委派，
     * 模型轮落库后只剩 4 步，受理必拒，白耗一次模型轮次）。
     */
    public static Facts withUpcomingModelStep(Facts facts) {
        return new Facts(
                facts.maxSteps(), facts.stepsUsed() + 1,
                facts.maxToolCalls(), facts.toolCallsUsed(),
                facts.maxInputTokens(), facts.inputTokensUsed(),
                facts.maxOutputTokens(), facts.outputTokensUsed(),
                facts.childrenUsed(), facts.maxChildren(),
                facts.combined(), facts.contextPolicyVersion());
    }

    /**
     * 工具可见性判定入口：与受理判定同一份规则，外加本轮已知的推进成本。
     * 受理判定与可见性判定由此共享唯一公式，不会出现"先可见、后被拒"的抖动。
     */
    public static boolean admitsDelegationForUpcomingTurn(Facts facts) {
        return reject(withUpcomingModelStep(facts)) == null;
    }
}
