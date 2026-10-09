package com.shitulelv.aicollab.infrastructure.ai.model;

import java.time.Duration;

/**
 * 本次出站请求的<b>有效单次输出上限</b>（内存中的请求参数派生，不改用户持久模型配置）。
 *
 * <p>单次最大输出与运行的整轮累计输出额度是两层不同限制。新策略下运行累计输出
 * 不限额，但<b>单次</b>请求仍必须遵守当前模型配置与本次实际窗口：
 * 协调器按"当前模型配置的单次最大输出"和"本次窗口能安全容纳的输出"取较小值，
 * 在本次调用范围内通过本上下文下发，使组装、窗口计算、输出封顶与出站请求使用同一份快照。</p>
 *
 * <p>作用域是本线程的一次出站请求，不修改数据库用户设置，也不跨请求泄漏；
 * 未设置时适配器退回配置值（历史行为）。</p>
 */
public final class AiRequestOutputCap implements AutoCloseable {
    private static final ThreadLocal<Integer> CAP = new ThreadLocal<>();
    private final Integer previous;

    public AiRequestOutputCap(int maxOutputTokens) {
        previous = CAP.get();
        CAP.set(maxOutputTokens > 0 ? maxOutputTokens : null);
    }

    /**
     * 本次请求的有效输出上限：未设置时返回 {@code configured}（配置值）。
     * 派生值永远不超过配置值，只可能因本次窗口更小而收紧。
     */
    public static int effective(int configured) {
        Integer cap = CAP.get();
        if (cap == null || cap <= 0) return configured;
        return Math.min(configured, cap);
    }

    /** 是否有本次请求的派生封顶（诊断用）。 */
    public static boolean active() {
        return CAP.get() != null;
    }

    @Override
    public void close() {
        if (previous == null) CAP.remove(); else CAP.set(previous);
    }

    /** 便于测试直接读取当前值。 */
    static Integer current() {
        return CAP.get();
    }

    /** 便于诊断的 Duration 视图（不改变语义）。 */
    public static Duration asDuration(int configured) {
        return Duration.ofSeconds(effective(configured));
    }
}
