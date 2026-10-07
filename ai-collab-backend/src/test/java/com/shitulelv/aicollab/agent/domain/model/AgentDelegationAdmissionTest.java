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

    // ==================================================================
    // v2：父子独立执行额度，不从父剩余切分
    // ==================================================================

    /** 生产 v2 新根运行：64 步 / 64 工具 / 无累计 token 上限 / 3 子运行。 */
    private static AgentDelegationAdmission.Facts v2Parent() {
        return new AgentDelegationAdmission.Facts(64, 0, 64, 0, null, 0, null, 0, 0, 3, false,
                AgentResourcePolicy.V2);
    }

    /**
     * v2 回归：父已用掉大部分自身工具额度后仍能发起委派，子取得<b>独立</b>额度。
     * v1 共享切分下，这种父剩余会直接把子压到最小额度以下（甚至拒绝受理）。
     */
    @Test
    void v2ParentWithMostToolsUsedStillAdmitsDelegationWithIndependentChildBudget() {
        var facts = new AgentDelegationAdmission.Facts(64, 40, 64, 60, null, 900_000, null, 500_000,
                1, 3, false, AgentResourcePolicy.V2);
        assertThat(AgentDelegationAdmission.reject(facts)).isNull();

        var budget = AgentDelegationAdmission.split(facts);
        // 子额度是自身的独立上限，不随父剩余缩水
        assertThat(budget.steps()).isEqualTo(AgentResourcePolicy.V2_CHILD_MAX_STEPS);
        assertThat(budget.toolCalls()).isEqualTo(AgentResourcePolicy.V2_CHILD_MAX_TOOL_CALLS);
        // 累计 token 不切分：v2 下没有"子只能读多少 token"的上限
        assertThat(budget.inputTokens()).isNull();
        assertThat(budget.outputTokens()).isNull();
    }

    /** v2 回归：父累计 token 远超旧 50k/子 30k 时，不因此隐藏或拒绝委派。 */
    @Test
    void v2CumulativeTokensNeverRejectDelegation() {
        var facts = new AgentDelegationAdmission.Facts(64, 10, 64, 5, null, 9_000_000, null, 5_000_000,
                0, 3, false, AgentResourcePolicy.V2);
        assertThat(AgentDelegationAdmission.reject(facts))
                .as("累计 token 不参与委派拒绝")
                .isNull();
    }

    /** v2 仍保证父自身能发起委派（1 次工具）并完成综合收尾（2 个推进步）。 */
    @Test
    void v2StillRequiresParentToAffordDelegationAndSynthesis() {
        // 父工具额度已耗尽：连委派自身那一次工具都放不下
        var noToolLeft = new AgentDelegationAdmission.Facts(64, 0, 64, 64, null, 0, null, 0, 0, 3,
                false, AgentResourcePolicy.V2);
        assertThat(AgentDelegationAdmission.reject(noToolLeft))
                .isEqualTo(AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET);

        // 父剩余推进步不足以完成综合收尾
        var noStepsLeft = new AgentDelegationAdmission.Facts(64, 63, 64, 0, null, 0, null, 0, 0, 3,
                false, AgentResourcePolicy.V2);
        assertThat(AgentDelegationAdmission.reject(noStepsLeft))
                .isEqualTo(AgentDelegationAdmission.RejectionReason.INSUFFICIENT_BUDGET);
    }

    // ==================================================================
    // F6：可见性判定必须计入本轮即将发生的已知推进成本
    // ==================================================================

    /**
     * F6 回归：剩余 5 步的 SEPARATED 运行，可见性判定若不计入本轮模型轮成本，
     * 会暴露一个"模型轮落库后必然被拒"的委派工具。修复后必须提前收窄。
     */
    @Test
    void f6VisibilityReservesTheUpcomingModelStep() {
        // 剩余 5 步：不计本轮成本时子可切出 3 步（≥ 最小 3）→ 旧实现会暴露委派
        var beforeTurn = new AgentDelegationAdmission.Facts(12, 7, 8, 0, 50_000, 0, 20_000, 0, 0, 3, false);

        // 旧口径：直接按当前事实判定 → 会认为可以委派
        assertThat(AgentDelegationAdmission.admitsDelegation(beforeTurn))
                .as("当前事实下切片恰好 3 步，旧实现据此暴露委派")
                .isTrue();

        // 修复口径：计入本轮模型轮（落库后 +1 步）→ 同一请求不得暴露必然被拒的委派
        assertThat(AgentDelegationAdmission.admitsDelegationForUpcomingTurn(beforeTurn))
                .as("模型轮落库后只剩 4 步，子最小 3 + 父收尾 2 已容不下")
                .isFalse();
        assertThat(AgentDelegationAdmission.reject(
                AgentDelegationAdmission.withUpcomingModelStep(beforeTurn)).code())
                .isEqualTo("AGENT_DELEGATION_BUDGET_INSUFFICIENT");
    }

    /** F6 回归：确有余额时不能因"预留一轮"而误收窄（避免过度拒绝）。 */
    @Test
    void f6VisibilityStillAdmitsWhenBudgetIsComfortable() {
        var comfortable = new AgentDelegationAdmission.Facts(12, 0, 8, 0, 50_000, 0, 20_000, 0, 0, 3, false);
        assertThat(AgentDelegationAdmission.admitsDelegationForUpcomingTurn(comfortable)).isTrue();
    }

    /** F6 回归：COMBINED 旧语义的推进步成本必须来自真实事实，不能固定假定 false。 */
    @Test
    void f6CombinedSemanticsUsesItsRealStepCost() {
        // COMBINED 下委派受理另占一个推进步：同一事实的有效剩余比 SEPARATED 少一步
        var separated = new AgentDelegationAdmission.Facts(12, 6, 8, 0, 50_000, 0, 20_000, 0, 0, 3, false);
        var combined = new AgentDelegationAdmission.Facts(12, 6, 8, 0, 50_000, 0, 20_000, 0, 0, 3, true);

        // 12 − 6 − 0 − 2 = 4（≥3 可受理）
        assertThat(AgentDelegationAdmission.reject(separated)).isNull();
        // 12 − 6 − 1 − 2 = 3：刚好满足最小值
        assertThat(AgentDelegationAdmission.split(combined).steps()).isEqualTo(3);
        // 再一步即容不下：可见性按真实语义收窄
        var combinedTighter = new AgentDelegationAdmission.Facts(12, 7, 8, 0, 50_000, 0, 20_000, 0, 0, 3, true);
        assertThat(AgentDelegationAdmission.admitsDelegationForUpcomingTurn(combinedTighter)).isFalse();
    }
}
