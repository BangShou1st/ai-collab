package com.shitulelv.aicollab.agent.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentSessionBudgetPolicy 边界全量覆盖（预算治理 v3，设计文档 §4.1）：
 * 剩余恰为 0、恰为收尾预留线、cap NULL / 0 / 负数、用量负值、预留负值的防御。
 */
class AgentSessionBudgetPolicyTest {

    private static final long CAP = 100_000L;
    private static final long RESERVE = AgentSessionBudgetPolicy.FINALIZE_RESERVE_TOKENS;

    // ========== EXHAUSTED：剩余 ≤ 0 ==========

    @Test
    void exactlyZeroRemainingIsExhausted() {
        // 剩余恰为 0：已用 == 上限 → 必须命中 EXHAUSTED（强制无工具收尾的边界）
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.EXHAUSTED);
    }

    @Test
    void overCapIsExhausted() {
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP + 1, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.EXHAUSTED);
        assertThat(AgentSessionBudgetPolicy.evaluate(Long.MAX_VALUE, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.EXHAUSTED);
    }

    // ========== FINALIZE：0 < 剩余 < 收尾预留 ==========

    @Test
    void justBelowReserveIsFinalize() {
        // 剩余 = 预留 - 1：进入收尾缓冲带
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP - RESERVE + 1, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.FINALIZE);
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP - 1, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.FINALIZE);
    }

    @Test
    void exactlyAtReserveIsNone() {
        // 恰为预留线：判定是严格小于，剩余 == 预留不算收尾
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP - RESERVE, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
    }

    @Test
    void wellWithinBudgetIsNone() {
        assertThat(AgentSessionBudgetPolicy.evaluate(0, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(1, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP - RESERVE - 1, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
    }

    // ========== 分级单调性：用量增长只升不降 ==========

    @Test
    void levelsProgressMonotonically() {
        AgentSessionBudgetPolicy.Level previous = null;
        for (long used = 0; used <= CAP; used += 1_000) {
            var level = AgentSessionBudgetPolicy.evaluate(used, CAP);
            if (previous != null) {
                assertThat(level.ordinal()).as("已用 %d 只能同级或升级", used)
                        .isGreaterThanOrEqualTo(previous.ordinal());
            }
            previous = level;
        }
    }

    // ========== cap 防御：NULL = 不限（存量会话），0/负数 = 关闭 ==========

    @Test
    void nullCapMeansUnlimited() {
        // 存量会话 cap NULL：任何用量都不动作（行为不变）
        assertThat(AgentSessionBudgetPolicy.evaluate(0, null)).isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP, null)).isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(Long.MAX_VALUE, null))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
    }

    @Test
    void zeroCapDisablesPolicy() {
        assertThat(AgentSessionBudgetPolicy.evaluate(0, 0L)).isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(1, 0L)).isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(Long.MAX_VALUE, 0L))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
    }

    @Test
    void negativeCapDisablesPolicy() {
        assertThat(AgentSessionBudgetPolicy.evaluate(1_000, -1L)).isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(Long.MAX_VALUE, Long.MIN_VALUE))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
    }

    // ========== 用量负值防御：脏数据不放大成"已超顶" ==========

    @Test
    void negativeUsageIsClampedToZero() {
        // 聚合列有 CHECK >= 0 兜底，应用层仍把负值钳到 0（NONE 而非误判额度状态）
        assertThat(AgentSessionBudgetPolicy.evaluate(-1, CAP)).isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(Long.MIN_VALUE, CAP))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        // 负值钳 0 后按真实剩余分级：cap=1 时剩余 1 进入收尾缓冲带，但绝不 EXHAUSTED
        assertThat(AgentSessionBudgetPolicy.evaluate(-1, 1L)).isEqualTo(AgentSessionBudgetPolicy.Level.FINALIZE);
    }

    // ========== 预留防御：预留 ≤ 0 只分 NONE / EXHAUSTED ==========

    @Test
    void nonPositiveReserveDisablesFinalizeLevel() {
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP - 1, CAP, 0))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP - 1, CAP, -1))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        // EXHAUSTED 判定不受预留影响
        assertThat(AgentSessionBudgetPolicy.evaluate(CAP, CAP, 0))
                .isEqualTo(AgentSessionBudgetPolicy.Level.EXHAUSTED);
    }

    // ========== 小上限边界 ==========

    @Test
    void tinyCapBoundaries() {
        // cap=1：未用 → NONE（剩余 1 ≥ 预留线不成立且 > 0，预留 20000 > 1 → FINALIZE）
        assertThat(AgentSessionBudgetPolicy.evaluate(0, 1L)).isEqualTo(AgentSessionBudgetPolicy.Level.FINALIZE);
        assertThat(AgentSessionBudgetPolicy.evaluate(1, 1L)).isEqualTo(AgentSessionBudgetPolicy.Level.EXHAUSTED);
    }

    // ========== 大上限不溢出 ==========

    @Test
    void largeCapDoesNotOverflow() {
        long bigCap = 1_000_000_000_000L;
        assertThat(AgentSessionBudgetPolicy.evaluate(0, bigCap)).isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(bigCap - RESERVE - 1, bigCap))
                .isEqualTo(AgentSessionBudgetPolicy.Level.NONE);
        assertThat(AgentSessionBudgetPolicy.evaluate(bigCap - RESERVE + 1, bigCap))
                .isEqualTo(AgentSessionBudgetPolicy.Level.FINALIZE);
        assertThat(AgentSessionBudgetPolicy.evaluate(bigCap, bigCap))
                .isEqualTo(AgentSessionBudgetPolicy.Level.EXHAUSTED);
    }

    // ========== 默认配置锚点 ==========

    @Test
    void defaultFinalizeReserveIsTwentyThousand() {
        // 收尾预留常量锚定设计值（§4.1 收尾预留，如 20000），改动需同步设计文档
        assertThat(AgentSessionBudgetPolicy.FINALIZE_RESERVE_TOKENS).isEqualTo(20_000L);
    }
}
