package com.shitulelv.aicollab.agent.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentBudgetThresholdPolicy 边界全量覆盖（设计文档 §4.2）：
 * 恰好在阈值、跨级、cap 为 0/负数的防御、非法用量的防御。
 */
class AgentBudgetThresholdPolicyTest {

    private static final long CAP = 150_000L;

    // ========== 常规默认软顶（150000）==========

    @Test
    void belowHintThresholdIsNone() {
        assertThat(AgentBudgetThresholdPolicy.evaluate(0, CAP)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
        assertThat(AgentBudgetThresholdPolicy.evaluate(1, CAP)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
        assertThat(AgentBudgetThresholdPolicy.evaluate(1_000, CAP)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
        // 恰好低于 60% 一位：89999
        assertThat(AgentBudgetThresholdPolicy.evaluate(89_999, CAP)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
    }

    @Test
    void exactlyAtHintThresholdIsConvergeHint() {
        // 150000 × 60% = 90000：恰好在阈值必须命中（含 60% 边界的双精度等值验证）
        assertThat(AgentBudgetThresholdPolicy.evaluate(90_000, CAP))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.CONVERGE_HINT);
        assertThat(AgentBudgetThresholdPolicy.evaluate(90_001, CAP))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.CONVERGE_HINT);
    }

    @Test
    void betweenThresholdsStaysConvergeHint() {
        assertThat(AgentBudgetThresholdPolicy.evaluate(100_000, CAP))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.CONVERGE_HINT);
        // 恰好低于 85% 一位：127499（150000 × 85% = 127500）
        assertThat(AgentBudgetThresholdPolicy.evaluate(127_499, CAP))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.CONVERGE_HINT);
    }

    @Test
    void exactlyAtRestrictThresholdIsRestrict() {
        assertThat(AgentBudgetThresholdPolicy.evaluate(127_500, CAP))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.RESTRICT);
        assertThat(AgentBudgetThresholdPolicy.evaluate(127_501, CAP))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.RESTRICT);
    }

    @Test
    void overCapIsRestrict() {
        assertThat(AgentBudgetThresholdPolicy.evaluate(150_000, CAP))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.RESTRICT);
        assertThat(AgentBudgetThresholdPolicy.evaluate(300_000, CAP))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.RESTRICT);
        assertThat(AgentBudgetThresholdPolicy.evaluate(Long.MAX_VALUE, CAP))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.RESTRICT);
    }

    // ========== 跨级推进（NONE → CONVERGE_HINT → RESTRICT）==========

    @Test
    void levelsProgressMonotonically() {
        AgentBudgetThresholdPolicy.Level previous = null;
        for (long used = 0; used <= CAP; used += 5_000) {
            var level = AgentBudgetThresholdPolicy.evaluate(used, CAP);
            if (previous != null) {
                assertThat(level.ordinal()).as("用量 %d 只能同级或升级", used)
                        .isGreaterThanOrEqualTo(previous.ordinal());
            }
            previous = level;
        }
    }

    // ========== 小软顶下的边界 ==========

    @Test
    void smallCapBoundaries() {
        assertThat(AgentBudgetThresholdPolicy.evaluate(5, 10)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
        assertThat(AgentBudgetThresholdPolicy.evaluate(6, 10)).isEqualTo(AgentBudgetThresholdPolicy.Level.CONVERGE_HINT);
        assertThat(AgentBudgetThresholdPolicy.evaluate(8, 10)).isEqualTo(AgentBudgetThresholdPolicy.Level.CONVERGE_HINT);
        assertThat(AgentBudgetThresholdPolicy.evaluate(9, 10)).isEqualTo(AgentBudgetThresholdPolicy.Level.RESTRICT);
        // cap=1：任何正用量都满级
        assertThat(AgentBudgetThresholdPolicy.evaluate(1, 1)).isEqualTo(AgentBudgetThresholdPolicy.Level.RESTRICT);
    }

    // ========== cap 防御（0/负数 = 关闭）==========

    @Test
    void zeroCapDisablesThresholds() {
        assertThat(AgentBudgetThresholdPolicy.evaluate(0, 0)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
        assertThat(AgentBudgetThresholdPolicy.evaluate(1, 0)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
        assertThat(AgentBudgetThresholdPolicy.evaluate(Long.MAX_VALUE, 0))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
    }

    @Test
    void negativeCapDisablesThresholds() {
        assertThat(AgentBudgetThresholdPolicy.evaluate(1_000, -1)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
        assertThat(AgentBudgetThresholdPolicy.evaluate(Long.MAX_VALUE, Long.MIN_VALUE))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
    }

    // ========== 用量防御（负数/异常输入）==========

    @Test
    void negativeUsageIsNone() {
        assertThat(AgentBudgetThresholdPolicy.evaluate(-1, CAP)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
        assertThat(AgentBudgetThresholdPolicy.evaluate(Long.MIN_VALUE, CAP)).isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
    }

    // ========== 大软顶不溢出 ==========

    @Test
    void largeCapDoesNotOverflow() {
        long bigCap = 1_000_000_000_000L;
        assertThat(AgentBudgetThresholdPolicy.evaluate(600_000_000_000L, bigCap))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.CONVERGE_HINT);
        assertThat(AgentBudgetThresholdPolicy.evaluate(850_000_000_000L, bigCap))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.RESTRICT);
        assertThat(AgentBudgetThresholdPolicy.evaluate(1, bigCap))
                .isEqualTo(AgentBudgetThresholdPolicy.Level.NONE);
    }
}
