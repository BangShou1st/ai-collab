package com.shitulelv.aicollab.agent.application.runtime;

import java.util.Map;

/**
 * 单次模型请求的容量计算。
 *
 * <p>三个量必须分开，不能混成一个数字：</p>
 * <ul>
 *   <li>{@code H} 模型安全可用输入：{@code W - R - S}，真正的<b>单次硬边界</b>；</li>
 *   <li>{@code T} 软压缩触发线：v2 为 {@code floor(H * 0.85)}，小窗口自动提前触发；
 *       v1 兼容路径为 {@code min(256000, floor(H * 0.85))}（仅恢复旧运行时使用）；</li>
 *   <li>{@code L} 压缩后活跃上下文软目标：{@code floor(T * 0.50)}。</li>
 * </ul>
 *
 * <p>{@code T} <b>不是</b>单次请求硬上限，也不是整轮累计额度。超过 {@code T} 触发整理旧轨迹，
 * 超过 {@code T} 不构成拒绝受理、截断必要证据或反复压缩的理由；只要请求仍在 {@code H} 内就可以继续。
 * {@code T} 也不是必须填满的目标：短问题仍应短请求直接完成。</p>
 *
 * <p>J0-2 起 v2 不再有 256k 绝对触发顶：{@code T} 完全是 {@code H} 的函数，大窗口模型的压缩
 * 触发随窗口等比后移。旧运行按持久化的 {@code context_policy_version} 解释——v1 继续使用
 * 256k 触发顶，恢复/续跑不会因新公式改变压缩节奏。文本计量统一由
 * {@code common.ai.token.TokenEstimator} 承担。</p>
 *
 * <p>模型窗口未知时进入显式<b>保守模式</b>：单次输入使用 50k 兼容回退，
 * {@code T = floor(50000 * 0.85) = 42500}，{@code Budget.conservativeMode} 为 true，
 * 由调用方记录 {@code agent.context.window.unknown} 指标与告警日志。这不是对任意未知
 * 小模型的安全保证——启用大窗口策略前必须核实并配置型号/端点容量。</p>
 *
 * <p>本类只消费调用方给出的 token 预算，不做字符→token 换算；文本计量统一由
 * {@code TokenEstimator} 承担。本类是纯函数组件：不读数据库、不读模型配置、不决定业务授权。</p>
 */
public final class AgentContextBudget {

    /**
     * v1 旧运行的软压缩触发线兼容上限（k 按 1000 计）。
     *
     * <p>仅当 {@code enforcePerRequestCap=true}（即持久化 {@code context_policy_version=1}）
     * 时生效：旧运行按自己的持久化策略版本继续解释，恢复/续跑时压缩节奏不变。
     * v2 运行<b>不</b>使用该常量，触发线完全由 {@code floor(H * 0.85)} 决定。</p>
     */
    public static final int V1_LEGACY_SOFT_COMPACTION_TRIGGER_TOKENS = 256_000;
    /** 触发线相对安全可用输入的候选比例（第一版策略起点，不是提供商协议）。 */
    public static final double TRIGGER_RATIO_OF_H = 0.85;
    /** 压缩后活跃上下文的软目标比例（不是硬约束，达不到不判失败）。 */
    public static final double POST_COMPACTION_TARGET_RATIO = 0.50;
    /**
     * 模型窗口未知时的保守回退单次上限（沿用既有兼容值）。
     */
    public static final int UNKNOWN_WINDOW_FALLBACK_TOKENS = 50_000;

    /** 约束来源：单次请求被哪一项绑定，超限时用于区分原因。 */
    public static final String BINDING_RUN_INPUT_BUDGET = "RUN_INPUT_BUDGET";
    public static final String BINDING_PER_REQUEST_CAP = "PER_REQUEST_CAP";
    public static final String BINDING_MODEL_WINDOW = "MODEL_WINDOW";

    /**
     * 模型窗口解析结果。
     *
     * @param windowTokens 已确认的上下文容量；{@code null} 表示未知
     * @param basis        命中来源（"类型:模型名" / "模型名" / "UNKNOWN"）
     * @param estimated    是否估算或未知（未知窗口不能宣称精确适配）
     */
    public record ModelWindow(Integer windowTokens, String basis, boolean estimated) {
        public static ModelWindow unknown() {
            return new ModelWindow(null, "UNKNOWN", true);
        }

        public boolean known() {
            return windowTokens != null && windowTokens > 0;
        }
    }

    /**
     * 单次请求容量。
     *
     * @param availableInputTokens 本次请求可用的输入 token 硬边界（{@code min(H, 运行剩余, 应用单次上限)}）
     * @param hardInputTokens      模型安全可用输入 {@code H}（未叠加运行累计与应用 cap）
     * @param softTriggerTokens    软压缩触发线 {@code T}
     * @param compactionTargetTokens 压缩后活跃上下文软目标 {@code L}
     * @param binding              绑定的约束来源
     * @param windowEstimated      窗口是否估算/未知（含未验证档案；未验证档案有数值可用）
     * @param conservativeMode     窗口是否真正未知、正在使用保守回退容量
     */
    public record Budget(
            int availableInputTokens,
            int hardInputTokens,
            int softTriggerTokens,
            int compactionTargetTokens,
            String binding,
            boolean windowEstimated,
            boolean conservativeMode) {

        /** 活跃上下文估算是否已达软压缩触发线（达到即整理旧轨迹，不拒绝请求）。 */
        public boolean shouldCompact(int activeContextTokens) {
            return activeContextTokens >= softTriggerTokens;
        }

        /** 兼容旧调用点：等价于 {@link #availableInputTokens()}。 */
        public int availableInput() {
            return availableInputTokens;
        }
    }

    private AgentContextBudget() {
    }

    /**
     * 按"提供商类型:模型名"或"模型名"匹配窗口覆盖；未命中返回未知窗口。
     * 运行所用配置经 {@code AgentModelConfigurationStore} 固定（快照），此处只做键匹配。
     */
    public static ModelWindow resolveWindow(String providerType, String modelName, Map<String, Integer> overrides) {
        if (overrides == null || overrides.isEmpty() || modelName == null || modelName.isBlank()) {
            return ModelWindow.unknown();
        }
        if (providerType != null && !providerType.isBlank()) {
            Integer typed = overrides.get(providerType + ":" + modelName);
            if (typed != null && typed > 0) return new ModelWindow(typed, providerType + ":" + modelName, false);
        }
        Integer plain = overrides.get(modelName);
        if (plain != null && plain > 0) return new ModelWindow(plain, modelName, false);
        return ModelWindow.unknown();
    }

    /**
     * 解析本次请求容量。
     *
     * <p>{@code remainingRunInputTokens} 只对 v1 运行有实际收紧作用；v2 运行传
     * {@link Integer#MAX_VALUE}（或调用方已解析的"无累计上限"），此时请求只受模型窗口与应用
     * 单次上限约束。{@code maxOutputForRequest} 是<b>本次实际出站</b>的最大输出额度，
     * 输出预留必须与出站值一致，不能用与实际发送不同的数字计算窗口。</p>
     */
    public static Budget perRequest(
            AgentContextProperties properties,
            ModelWindow window,
            int remainingRunInputTokens,
            int maxOutputForRequest) {
        return perRequest(properties, window, remainingRunInputTokens, maxOutputForRequest, true);
    }

    /** 兼容入口：未显式给出本次出站最大输出时用配置的输出预留。 */
    public static Budget perRequest(
            AgentContextProperties properties, ModelWindow window, int remainingRunInputTokens) {
        return perRequest(properties, window, remainingRunInputTokens, 0, true);
    }

    /**
     * 解析本次请求容量（策略版本感知）。
     *
     * <p>{@code enforcePerRequestCap=false}（v2 且模型窗口已知）时不再叠加应用单次上限：
     * 单次请求的真实硬边界就是模型安全可用输入 {@code H}，移除"已确认大模型上额外的 50k 硬限"。
     * 窗口未知时仍保留既有 50k 兼容回退（并标记估算与 {@code conservativeMode}）——
     * 这不是对未知小模型的安全保证。</p>
     *
     * <p>触发线策略版本感知：{@code enforcePerRequestCap=true} 的 v1 运行沿用 256k 兼容上限，
     * 保证旧运行恢复/续跑按持久化策略解释；v2 运行使用 {@code T = floor(H * 0.85)}，不再有
     * 绝对 256k 顶。两者都不在准入层收紧 {@code availableInputTokens}。</p>
     */
    public static Budget perRequest(
            AgentContextProperties properties,
            ModelWindow window,
            int remainingRunInputTokens,
            int maxOutputForRequest,
            boolean enforcePerRequestCap) {
        int outputReserve = maxOutputForRequest > 0
                ? maxOutputForRequest
                : Math.max(0, properties.outputReserveTokens());
        int safety = Math.max(0, properties.safetyMarginTokens());

        // 模型安全可用输入 H：已知窗口时是真实硬边界；未知窗口时用兼容回退，并标记保守模式
        boolean windowKnown = window != null && window.known();
        boolean conservativeMode = !windowKnown;
        int hardInput;
        boolean windowEstimated;
        if (windowKnown) {
            hardInput = window.windowTokens() - outputReserve - safety;
            // 未验证档案有数值可用，但仍属估算语义；只有真正未知才进入保守模式
            windowEstimated = window.estimated();
        } else {
            hardInput = UNKNOWN_WINDOW_FALLBACK_TOKENS;
            windowEstimated = true;
        }
        if (hardInput < 0) hardInput = 0;

        // 软压缩触发线 T：v2 = floor(H*0.85)，完全随窗口缩放；v1 额外套用 256k 旧兼容上限。
        // T 只用于触发压缩，不收紧可用输入——超过 T 是"触发压缩"，不是"拒绝请求"。
        int trigger = (int) Math.floor(hardInput * TRIGGER_RATIO_OF_H);
        if (enforcePerRequestCap) {
            trigger = Math.min(V1_LEGACY_SOFT_COMPACTION_TRIGGER_TOKENS, trigger);
        }
        if (trigger < 0) trigger = 0;
        // 压缩后软目标 L = floor(T*0.50)；软目标，不是失败判据
        int target = (int) Math.floor(trigger * POST_COMPACTION_TARGET_RATIO);

        // 单次可用输入 = min(H, 运行剩余额度, [仅 v1/未知窗口] 应用单次上限)。
        // 未知窗口时 H 本身就是"应用保守回退上限"，因此初值如实标为 PER_REQUEST_CAP，
        // 不谎称有已知模型窗口或运行剩余额度在限制。
        int available = hardInput;
        String binding = conservativeMode ? BINDING_PER_REQUEST_CAP : BINDING_MODEL_WINDOW;
        if (remainingRunInputTokens < available) {
            available = Math.max(0, remainingRunInputTokens);
            binding = BINDING_RUN_INPUT_BUDGET;
        }
        // 应用单次上限只在 v1 或窗口真正未知（保守模式）时起作用：未验证档案有数值可用，
        // 窗口已知的 v2 运行以 H 为硬边界，不再被额外的应用单次上限（默认 50k）绑住。
        if ((enforcePerRequestCap || conservativeMode) && properties.perRequestInputCap() < available) {
            available = properties.perRequestInputCap();
            binding = BINDING_PER_REQUEST_CAP;
        }
        if (available > hardInput) available = hardInput;
        if (available < 0) available = 0;
        return new Budget(available, hardInput, trigger, target, binding, windowEstimated, conservativeMode);
    }
}
