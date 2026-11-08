package com.shitulelv.aicollab.agent.domain.policy;

/**
 * 会话聚合 token 预算分级（预算治理 v3，设计文档 §4.1，P1 范围）。
 *
 * <p>背景：v2 预算语义下 run 的累计 token 上限为 NULL、子运行预算独立不从父切分，
 * 一次会话的聚合消耗只有步数间接约束。会话聚合硬顶持久化在 {@code agent_session}
 * 的 {@code max_aggregate_tokens}（NULL = 不限，兼容存量会话）。本策略按剩余额度分级：</p>
 *
 * <ul>
 *   <li>{@link Level#NONE}：剩余充足，不动作；</li>
 *   <li>{@link Level#FINALIZE}：剩余低于收尾预留，仅注入收敛提示（引导提前收敛）；</li>
 *   <li>{@link Level#EXHAUSTED}：剩余 ≤ 0，本轮强制无工具收尾（剥离工具定义 +
 *       注入收敛提示），保证产出部分结果而非硬杀。</li>
 * </ul>
 *
 * <p>纯函数、无状态。与 run 级软阈值（{@link AgentBudgetThresholdPolicy}）独立分级：
 * 判据取不同列（会话聚合 {@code aggregate_tokens_used} vs run 真实输入
 * {@code input_tokens_actual}），动作可叠加；两类提示文案刻意不同，
 * 同轮叠加注入不会重复同一文案。</p>
 *
 * <p>准入式取舍（设计文档 §8）：准入检查后记账仍在同一事务，DB 端
 * {@code agent_capped_add} 保证聚合不超顶；最坏情况只超一个模型轮的量。</p>
 */
public final class AgentSessionBudgetPolicy {

    /**
     * 收尾预留：会话剩余额度低于该值时提前进入收敛引导（EXHAUSTED 之前的缓冲带），
     * 给"无工具收尾轮"留出输入 + 输出余量。取值依据：一次收尾请求的典型输入 +
     * 最大输出余量，量级与 AgentContextProperties.outputReserveTokens 相当。
     */
    public static final long FINALIZE_RESERVE_TOKENS = 20_000L;

    private AgentSessionBudgetPolicy() {
    }

    /** 使用默认收尾预留（{@value #FINALIZE_RESERVE_TOKENS}）的分级评估。 */
    public static Level evaluate(long aggregateTokensUsed, Long maxAggregateTokens) {
        return evaluate(aggregateTokensUsed, maxAggregateTokens, FINALIZE_RESERVE_TOKENS);
    }

    /**
     * 分级评估。
     *
     * @param aggregateTokensUsed   会话聚合已用 token（aggregate_tokens_used）
     * @param maxAggregateTokens    会话聚合上限；NULL = 不限（存量会话），≤ 0 防御性视为关闭
     *                              （与 {@link AgentBudgetThresholdPolicy} 的 cap 防御语义一致，
     *                              配置/数据错误不阻断运行）
     * @param finalizeReserveTokens 收尾预留；≤ 0 防御性视为无预留（只分 NONE / EXHAUSTED）
     */
    public static Level evaluate(long aggregateTokensUsed, Long maxAggregateTokens, long finalizeReserveTokens) {
        // NULL = 不限（存量会话语义不变）；cap ≤ 0 防御性视为关闭
        if (maxAggregateTokens == null || maxAggregateTokens <= 0) return Level.NONE;
        // 负值防御：聚合列有 CHECK >= 0 兜底，应用层仍不把脏数据放大成"已超顶"
        long used = Math.max(0, aggregateTokensUsed);
        long remaining = maxAggregateTokens - used;
        if (remaining <= 0) return Level.EXHAUSTED;
        // 恰好等于预留线时不算收尾（判定是严格小于）：预留是缓冲带，不是阈值本身
        if (finalizeReserveTokens > 0 && remaining < finalizeReserveTokens) return Level.FINALIZE;
        return Level.NONE;
    }

    /** 会话聚合预算分级结果。 */
    public enum Level {
        NONE,
        FINALIZE,
        EXHAUSTED
    }
}
