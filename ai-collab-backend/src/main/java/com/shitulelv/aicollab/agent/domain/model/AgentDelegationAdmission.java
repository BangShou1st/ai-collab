package com.shitulelv.aicollab.agent.domain.model;

/**
 * document_research 委派的受理判定与子运行预算切分（纯函数，无数据库、无配置、可单测）。
 *
 * <p>把"委派能否受理"从"委派执行失败"里分离出来：<b>可预期的受理拒绝</b>
 * （委派次数耗尽、剩余预算容不下子运行最小研究与父综合收尾）是有确定原因的
 * 业务事实，调用方据此有边界地保留父运行；权限、安全、暂停、取消、租约与内部
 * 执行异常不属于本判定，仍走原失败边界。</p>
 *
 * <p>只按数值与类型判定，<b>不</b>根据异常消息文本判断原因；原因以
 * {@link RejectionReason} 枚举显式表达。</p>
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
        /** 父运行剩余预算连子运行最小研究与收尾都容纳不了。 */
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

    /** 参与受理判定的运行事实快照（由调用方从运行视图取值，避免 domain 反向依赖 application）。 */
    public record Facts(
            int maxSteps, int stepsUsed,
            int maxToolCalls, int toolCallsUsed,
            int maxInputTokens, int inputTokensUsed,
            int maxOutputTokens, int outputTokensUsed,
            int childrenUsed, int maxChildren,
            boolean combined) {
    }

    /** 子运行可用预算（父剩余额度切分结果）。 */
    public record ChildBudget(int steps, int toolCalls, int inputTokens, int outputTokens) {
    }

    private AgentDelegationAdmission() {}

    /**
     * 从父运行剩余额度切出子运行预算：委派自身占一次工具调用，父综合收尾预留 2 个推进步，
     * COMBINED 旧语义下委派受理另占一个推进步；输入/输出按父剩余等比对半（封顶）。
     */
    public static ChildBudget split(Facts facts) {
        int delegationStepCost = facts.combined() ? 1 : 0;
        int steps = Math.min(MAX_CHILD_STEPS, Math.max(0,
                facts.maxSteps() - facts.stepsUsed() - delegationStepCost - PARENT_SYNTHESIS_RESERVE_STEPS));
        int toolCalls = Math.min(MAX_CHILD_TOOL_CALLS, Math.max(0,
                facts.maxToolCalls() - facts.toolCallsUsed() - DELEGATION_TOOL_CALL_COST));
        int inputTokens = Math.min(MAX_CHILD_INPUT_TOKENS, Math.max(0,
                (facts.maxInputTokens() - facts.inputTokensUsed()) / 2));
        int outputTokens = Math.min(MAX_CHILD_OUTPUT_TOKENS, Math.max(0,
                (facts.maxOutputTokens() - facts.outputTokensUsed()) / 2));
        return new ChildBudget(steps, toolCalls, inputTokens, outputTokens);
    }

    /**
     * 可预期的受理拒绝原因；{@code null} 表示可以受理。
     * 判定只看运行事实，同一运行事实下结论稳定（恢复重放得到相同结果）。
     */
    public static RejectionReason reject(Facts facts) {
        if (facts.childrenUsed() >= facts.maxChildren()) return RejectionReason.CHILDREN_EXHAUSTED;
        ChildBudget budget = split(facts);
        if (budget.steps() < MIN_CHILD_STEPS || budget.toolCalls() < MIN_CHILD_TOOL_CALLS
                || budget.inputTokens() < MIN_CHILD_INPUT_TOKENS
                || budget.outputTokens() < MIN_CHILD_OUTPUT_TOKENS) {
            return RejectionReason.INSUFFICIENT_BUDGET;
        }
        return null;
    }

    /** 当前运行是否还能受理新的委派（供工具可见性收窄使用）。 */
    public static boolean admitsDelegation(Facts facts) {
        return reject(facts) == null;
    }
}
