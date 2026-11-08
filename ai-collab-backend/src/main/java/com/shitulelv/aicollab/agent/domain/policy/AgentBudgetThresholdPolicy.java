package com.shitulelv.aicollab.agent.domain.policy;

/**
 * run 级输入预算软阈值分级（预算治理 v3，设计文档 §4.2，P0 范围）。
 *
 * <p>背景：v2 预算语义下 run 的累计 token 上限为 NULL（只记账不管控），
 * 全链路没有软阈值。本策略依据<b>持久化的真实累计输入列</b>
 * （{@code input_tokens_actual}，非封顶的 {@code input_tokens_used}）对配置的
 * 引导性软顶做分级：</p>
 *
 * <ul>
 *   <li>{@link Level#NONE}：未达阈值，不动作；</li>
 *   <li>{@link Level#CONVERGE_HINT}：≥ 软顶 60%，向本轮提示注入收敛提示；</li>
 *   <li>{@link Level#RESTRICT}：≥ 软顶 85%，注入更强收敛提示并上报指标。</li>
 * </ul>
 *
 * <p>纯函数、无状态。分级依据是单调增长的持久化累计列，每个模型轮重算天然幂等，
 * 不需要额外的"已提示"标记；软顶是<b>引导性软顶，非硬准入</b>——只做"提示 + 指标"，
 * 不改变准入与收敛判定（{@code AgentConvergencePolicy.needsFinalRequest} 语义不变）。</p>
 */
public final class AgentBudgetThresholdPolicy {

    /** 收敛提示阈值：累计真实输入达到软顶的 60% 时注入一次性收敛提示。 */
    public static final double CONVERGE_HINT_RATIO = 0.60;

    /** 强收敛阈值：累计真实输入达到软顶的 85% 时注入更强收敛提示并上报指标。 */
    public static final double RESTRICT_RATIO = 0.85;

    private AgentBudgetThresholdPolicy() {
    }

    /**
     * 分级评估。
     *
     * @param inputTokensActual 本 run 持久化的真实累计输入 token（input_tokens_actual）
     * @param softInputTokenCap 配置的引导性软顶；0 或负数视为关闭（防御性，返回 NONE）
     */
    public static Level evaluate(long inputTokensActual, long softInputTokenCap) {
        // 软顶关闭（0/负数）或用量非法（负数）时一律不动作，防御配置与数据异常
        if (softInputTokenCap <= 0 || inputTokensActual <= 0) return Level.NONE;
        double ratio = (double) inputTokensActual / softInputTokenCap;
        if (ratio >= RESTRICT_RATIO) return Level.RESTRICT;
        if (ratio >= CONVERGE_HINT_RATIO) return Level.CONVERGE_HINT;
        return Level.NONE;
    }

    /** 软阈值分级结果。 */
    public enum Level {
        NONE,
        CONVERGE_HINT,
        RESTRICT
    }
}
