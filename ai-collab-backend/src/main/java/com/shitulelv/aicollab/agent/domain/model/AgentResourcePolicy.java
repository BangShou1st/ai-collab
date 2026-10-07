package com.shitulelv.aicollab.agent.domain.model;

import java.time.Duration;

/**
 * 运行资源策略解析（纯函数，无数据库、无配置读取、可单测）。
 *
 * <p>本类把"用哪一套资源语义"集中成唯一判据，避免同一事实在
 * Worker / 协调器 / 收敛策略 / 委派受理 / 录制仓储里各写一份判断而漂移。</p>
 *
 * <h2>策略版本</h2>
 * <ul>
 *   <li>{@link #V1}（既有行默认）：累计 {@code max_input_tokens}/{@code max_output_tokens}
 *       参与准入、收敛、委派切分与停机；恢复与暂停续跑保持原额度含义。</li>
 *   <li>{@link #V2}（新根运行与终态重试派生）：单次请求守当前模型真实窗口与本次最大输出；
 *       累计输入/输出<b>只统计</b>真实/估算用量，<b>不设累计上限</b>，不参与准入、收敛、
 *       摘要准入、兜底请求或委派拒绝。防失控由有限决策轮次、推进步、工具次数、子运行数量、
 *       活跃时长、重试次数与循环检测承担。</li>
 * </ul>
 *
 * <p><b>无累计上限用 {@code null} 表达</b>：不用 0、8M、{@code Integer.MAX_VALUE}
 * 或另一组更大的数字冒充"关闭限制"。调用方必须显式处理可空上限，
 * 不得把 {@code null} 读成 0，也不得对 {@code null} 做旧的 min/比较/对半分配。</p>
 *
 * <h2>父子独立执行额度（仅 V2）</h2>
 * 子运行的推进步、工具次数与活跃时长是<b>自己的独立上限</b>，不从父运行剩余额度切分；
 * 子消耗回收只用于真实统计（一次），不扣减父自身的步骤/工具额度。委派本身仍计父一次工具调用。
 */
public final class AgentResourcePolicy {

    /** 既有行为：累计 token 上限参与准入/收敛/切分/停机。 */
    public static final int V1 = 1;
    /** 新策略：累计 token 只统计；单次请求守当前模型窗口与单次输出；父子独立执行额度。 */
    public static final int V2 = 2;

    /** V2 根运行自身决策模型轮上限（执行保护起点，不是必须用满的计划）。 */
    public static final int V2_ROOT_MAX_MODEL_TURNS = 24;
    /** V2 根运行自身推进步上限。 */
    public static final int V2_ROOT_MAX_STEPS = 64;
    /** V2 根运行自身工具调用上限（委派本身计 1 次）。 */
    public static final int V2_ROOT_MAX_TOOL_CALLS = 64;
    /** V2 子运行自身决策模型轮上限；独立于父剩余轮次。 */
    public static final int V2_CHILD_MAX_MODEL_TURNS = 12;
    /** V2 子运行自身推进步上限；独立于父剩余步数。 */
    public static final int V2_CHILD_MAX_STEPS = 16;
    /** V2 子运行自身工具调用上限；独立于父剩余工具数。 */
    public static final int V2_CHILD_MAX_TOOL_CALLS = 24;
    /** 单轮最多提出的工具数（两种策略一致）。 */
    public static final int MAX_TOOL_CALLS_PER_TURN = 4;

    /** V2 根运行活跃执行时长（含模型/工具等待，不含排队/暂停/离线/父等待子）。 */
    public static final Duration V2_ROOT_ACTIVE_BUDGET = Duration.ofMinutes(45);
    /** V2 子运行活跃执行时长；不继承父剩余 deadline。 */
    public static final Duration V2_CHILD_ACTIVE_BUDGET = Duration.ofMinutes(30);

    /** V2 内置工具默认超时。 */
    public static final Duration V2_INTERNAL_TOOL_TIMEOUT = Duration.ofSeconds(30);
    /** V2 MCP 工具默认超时。 */
    public static final Duration V2_MCP_TOOL_TIMEOUT = Duration.ofSeconds(120);
    /**
     * V2 工具结果字节保护。
     *
     * <p>正文页放大到 24000 字符后，一页最多横跨约 40 个 chunk（chunker 块长 600–1200），
     * 每块再各带一条最多 600 字符的引用摘录；实测最坏序列化约 160kB。
     * 128kB 会在该场景触发 {@code AgentToolResultSanitizer} 的降级截断（表现为
     * 误导性的"资料不足"），因此取 256kB（与既有 MCP {@code ck_agent_mcp_limits}
     * 的 {@code max_result_bytes} 上界一致）。仍是<b>有界</b>值，不是取消保护：
     * 超限结果照样被标记截断。</p>
     */
    public static final int V2_MAX_TOOL_RESULT_BYTES = 256 * 1024;

    private AgentResourcePolicy() {
    }

    /** 规范化策略版本：未知/非法值按既有 v1 兼容处理，不猜测新语义。 */
    public static int normalize(Integer version) {
        return version != null && version == V2 ? V2 : V1;
    }

    /** 是否执行累计输入/输出上限（只有 v1 执行）。 */
    public static boolean enforcesCumulativeTokenLimits(int version) {
        return normalize(version) == V1;
    }

    /**
     * 有效累计输入上限。{@code null} 表示<b>无累计上限</b>（只统计，不参与任何准入判定）。
     *
     * <p>v1 沿用原有 {@code min(运行上限, 技能上限)} 语义；v2 明确返回 {@code null}，
     * 不再叠加任何替代额度。</p>
     */
    public static Integer effectiveInputCap(int version, Integer runCap, Integer limitsCap) {
        if (!enforcesCumulativeTokenLimits(version)) return null;
        return v1Cap(runCap, limitsCap);
    }

    /** 有效累计输出上限。{@code null} 表示无累计上限（单次最大输出仍由模型配置与本次窗口约束）。 */
    public static Integer effectiveOutputCap(int version, Integer runCap, Integer limitsCap) {
        if (!enforcesCumulativeTokenLimits(version)) return null;
        return v1Cap(runCap, limitsCap);
    }

    /** v1 兼容取值：任一侧为 null 时按另一侧处理；两侧都为 null 表示该运行没有该额度事实。 */
    private static Integer v1Cap(Integer runCap, Integer limitsCap) {
        if (runCap == null) return limitsCap;
        if (limitsCap == null) return runCap;
        return Math.min(runCap, limitsCap);
    }

    /**
     * 上限下的剩余量；上限为 {@code null}（无累计上限）时返回 {@link Long#MAX_VALUE}。
     * 调用方据此判断"是否已被累计额度耗尽"——无上限的运行永不因累计 token 耗尽。
     */
    public static long remaining(Integer cap, long used) {
        if (cap == null) return Long.MAX_VALUE;
        return (long) cap - used;
    }

    /**
     * 累计输入是否已到限（无累计上限时恒为 false）。
     *
     * @param usedTokens 预算语义的已用累计值（{@code input_tokens_used}，v1 下由 LEAST 封顶）。
     *                   这是"准入下一次请求"的判据，与 {@code *_actual}（真实消耗，
     *                   可用于超额审计）是不同用途，不要混用。
     */
    public static boolean inputExhausted(int version, Integer runCap, Integer limitsCap, long usedTokens) {
        Integer cap = effectiveInputCap(version, runCap, limitsCap);
        return cap != null && usedTokens >= cap;
    }

    /** 累计输出是否已到限（无累计上限时恒为 false）。判据同 {@link #inputExhausted}。 */
    public static boolean outputExhausted(int version, Integer runCap, Integer limitsCap, long usedTokens) {
        Integer cap = effectiveOutputCap(version, runCap, limitsCap);
        return cap != null && usedTokens >= cap;
    }

    /**
     * V2 运行自身执行限制：根 24 轮/64 步/64 工具、子 12 轮/16 步/24 工具，
     * 活跃时长根 45 分钟、子 30 分钟，超时与结果字节保护同步放宽。
     *
     * <p>这些是保护上界，不是任务计划；达到用户目标即结束。子运行的上限独立于父剩余额度。</p>
     */
    public static AgentRuntimeLimits v2Limits(int depth) {
        boolean child = depth > 0;
        return new AgentRuntimeLimits(
                child ? V2_CHILD_MAX_STEPS : V2_ROOT_MAX_STEPS,
                child ? V2_CHILD_MAX_MODEL_TURNS : V2_ROOT_MAX_MODEL_TURNS,
                child ? V2_CHILD_MAX_TOOL_CALLS : V2_ROOT_MAX_TOOL_CALLS,
                MAX_TOOL_CALLS_PER_TURN,
                child ? V2_CHILD_ACTIVE_BUDGET : V2_ROOT_ACTIVE_BUDGET,
                V2_INTERNAL_TOOL_TIMEOUT,
                V2_MCP_TOOL_TIMEOUT,
                V2_MAX_TOOL_RESULT_BYTES,
                null,
                null);
    }
}
