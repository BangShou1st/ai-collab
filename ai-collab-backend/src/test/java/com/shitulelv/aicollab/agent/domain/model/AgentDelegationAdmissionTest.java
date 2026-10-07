package com.shitulelv.aicollab.agent.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派受理判定与子运行预算切分（{@link AgentDelegationAdmission}）单测：纯函数，无数据库。
 * 锁定"可预期受理拒绝"的类型化原因与切分公式，不依赖异常消息文本。
 */
class AgentDelegationAdmissionTest {

    /** 生产默认根运行：12 步 / 8 工具 / 50000 输入 / 20000 输出 / 3 子运行。 */
    private static AgentDelegationAdmission.Facts parent() {
        return new AgentDelegationAdmission.Facts(12, 0, 8, 0, 50_000, 0, 20_000, 0, 0, 3, false);
    }

    private static AgentDelegationAdmission.Facts withSteps(int stepsUsed) {
        var base = parent();
        return new AgentDelegationAdmission.Facts(base.maxSteps(), stepsUsed, base.maxToolCalls(),
                base.toolCallsUsed(), base.maxInputTokens(), base.inputTokensUsed(),
                base.maxOutputTokens(), base.outputTokensUsed(), base.childrenUsed(), base.maxChildren(),
                base.combined());
    }

    @Test
    void fullBudgetParentAdmitsDelegationAndSplitsByReserve() {
        var facts = parent();
        assertThat(AgentDelegationAdmission.reject(facts)).isNull();
        assertThat(AgentDelegationAdmission.admitsDelegation(facts)).isTrue();

        var budget = AgentDelegationAdmission.split(facts);
        // 子步数 = min(8, 12 − 0 − 0（SEPARATED 委派不占推进步）− 父综合收尾预留 2)
        assertThat(budget.steps()).isEqualTo(8);
        // 子工具 = min(8, 8 − 0 − 委派自身 1)
        assertThat(budget.toolCalls()).isEqualTo(7);
        assertThat(budget.inputTokens()).isEqualTo(25_000);
        assertThat(budget.outputTokens()).isEqualTo(8_000);
    }

    /** 委派次数耗尽：可预期拒绝，原因码稳定（不按异常消息判定）。 */
    @Test
    void exhaustedChildrenIsReportedAsExplicitRejectionReason() {
        var base = parent();
        var exhausted = new AgentDelegationAdmission.Facts(base.maxSteps(), base.stepsUsed(),
                base.maxToolCalls(), base.toolCallsUsed(), base.maxInputTokens(), base.inputTokensUsed(),
                base.maxOutputTokens(), base.outputTokensUsed(), 3, 3, base.combined());

        assertThat(AgentDelegationAdmission.reject(exhausted))
                .isEqualTo(AgentDelegationAdmission.RejectionReason.CHILDREN_EXHAUSTED);
        assertThat(AgentDelegationAdmission.reject(exhausted).code())
                .isEqualTo("AGENT_DELEGATION_CHILDREN_EXHAUSTED");
        assertThat(AgentDelegationAdmission.admitsDelegation(exhausted)).isFalse();
    }

    /** 剩余推进步容不下子运行最小研究与父综合收尾：可预期拒绝。 */
    @Test
    void insufficientProgressionBudgetIsReportedAsExplicitRejectionReason() {
        // 剩余 3 步：扣除父综合收尾预留 2 后只剩 1 < 子最小 3
        var rejection = AgentDelegationAdmission.reject(withSteps(9));
        assertThat(rejection).isEqualTo(AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET);
        assertThat(rejection.code()).isEqualTo("AGENT_DELEGATION_BUDGET_INSUFFICIENT");
        assertThat(rejection.message()).contains("拒绝受理");
    }

    /** 工具/输入/输出任一维度不足都判定为预算不足，不因一个维度充足而放行注定失败的委派。 */
    @Test
    void anyInsufficientDimensionRejectsAdmission() {
        assertThat(AgentDelegationAdmission.reject(new AgentDelegationAdmission.Facts(
                12, 0, 1, 0, 50_000, 0, 20_000, 0, 0, 3, false)))
                .as("剩余工具额度扣除委派自身后为 0").isEqualTo(
                        AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET);
        assertThat(AgentDelegationAdmission.reject(new AgentDelegationAdmission.Facts(
                12, 0, 8, 0, 1_999, 0, 20_000, 0, 0, 3, false)))
                .as("输入剩余一半 999 < 1000").isEqualTo(
                        AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET);
        assertThat(AgentDelegationAdmission.reject(new AgentDelegationAdmission.Facts(
                12, 0, 8, 0, 2_000, 0, 20_000, 0, 0, 3, false)))
                .as("输入剩余一半恰好 1000：满足最小值，不因取整被误拒")
                .isNull();
        assertThat(AgentDelegationAdmission.reject(new AgentDelegationAdmission.Facts(
                12, 0, 8, 0, 50_000, 0, 1_999, 0, 0, 3, false)))
                .as("输出剩余一半 999 < 1000").isEqualTo(
                        AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET);
        assertThat(AgentDelegationAdmission.reject(new AgentDelegationAdmission.Facts(
                12, 0, 8, 0, 50_000, 0, 2_000, 0, 0, 3, false)))
                .as("输出剩余一半恰好 1000：满足最小值")
                .isNull();
    }

    /** COMBINED 旧语义下委派受理另占一个推进步：同一切分必须反映该成本。 */
    @Test
    void combinedSemanticsChargesDelegationStepInSplit() {
        var base = parent();
        var combined = new AgentDelegationAdmission.Facts(base.maxSteps(), base.stepsUsed(),
                base.maxToolCalls(), base.toolCallsUsed(), base.maxInputTokens(), base.inputTokensUsed(),
                base.maxOutputTokens(), base.outputTokensUsed(), base.childrenUsed(), base.maxChildren(), true);
        // 12 − 0 − 1（委派步成本）− 2 = 9，封顶 8
        assertThat(AgentDelegationAdmission.split(combined).steps()).isEqualTo(8);

        var nearBoundary = new AgentDelegationAdmission.Facts(12, 6, 8, 0, 50_000, 0, 20_000, 0, 0, 3, true);
        // 12 − 6 − 1 − 2 = 3：刚好满足最小值
        assertThat(AgentDelegationAdmission.split(nearBoundary).steps()).isEqualTo(3);
        assertThat(AgentDelegationAdmission.reject(nearBoundary)).isNull();
        // 再消耗一步即容不下
        var oneMore = new AgentDelegationAdmission.Facts(12, 7, 8, 0, 50_000, 0, 20_000, 0, 0, 3, true);
        assertThat(AgentDelegationAdmission.reject(oneMore))
                .isEqualTo(AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET);
    }

    /** 判定只依赖运行事实：同一事实重复判定结论一致（恢复重放不产生新的拒绝语义）。 */
    @Test
    void rejectionIsStableForTheSameFacts() {
        var facts = withSteps(9);
        for (int repeat = 0; repeat < 5; repeat++) {
            assertThat(AgentDelegationAdmission.reject(facts))
                    .isEqualTo(AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET);
        }
        // 子运行（depth>0）不能再委派由调用方按 depth 拦截，本判定不把 depth 混入预算原因
        var child = new AgentDelegationAdmission.Facts(8, 0, 7, 0, 25_000, 0, 8_000, 0, 0, 0, false);
        assertThat(AgentDelegationAdmission.reject(child))
                .as("子运行 maxChildren=0 → 委派次数耗尽原因").isEqualTo(
                        AgentDelegationAdmission.RejectionReason.CHILDREN_EXHAUSTED);
        // 原因码与消息都是稳定常量：判定不依赖异常消息文本
        assertThat(AgentDelegationAdmission.RejectionReason.CHILDREN_EXHAUSTED.code())
                .isNotEqualTo(AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET.code());
        assertThat(AgentDelegationAdmission.RejectionReason.CHILDREN_EXHAUSTED.message())
                .isNotBlank().isNotEqualTo(AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET.message());
    }
}
