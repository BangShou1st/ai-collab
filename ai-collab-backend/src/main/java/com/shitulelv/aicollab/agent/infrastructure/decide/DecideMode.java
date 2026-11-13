package com.shitulelv.aicollab.agent.infrastructure.decide;

import java.util.Locale;

/**
 * Agent decide 模式（W4 冻结契约 §3）：决定 Java 是否把工具轮决策交给 Python worker。
 *
 * <ul>
 *   <li>{@link #JAVA}（默认）：零外呼、零快照、零指标，行为与未实现时完全一致；</li>
 *   <li>{@link #SHADOW}：只在满足全部条件的工具轮边界发起后台调用，任何失败只记指标；</li>
 *   <li>{@link #PYTHON}：由 {@code DecideRouter} 在根运行、只读
 *       {@code PROJECT_RESEARCH} 边界同步消费 Python decision；任何不可信结果回退 Java。</li>
 * </ul>
 *
 * <p>取值大小写不敏感；未知值必须让应用启动失败，不得静默回退到默认值。</p>
 */
public enum DecideMode {

    JAVA,
    SHADOW,
    PYTHON;

    /**
     * 解析配置值。
     *
     * @param raw 原始配置值（可为 null）
     * @return 对应模式
     * @throws IllegalStateException 值为空或未知（启动失败）
     */
    public static DecideMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "decide.mode 不能为空，允许取值：java | shadow | python");
        }
        String normalized = raw.trim();
        for (DecideMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "未知的 decide.mode：" + normalized
                        + "，允许取值：java | shadow | python");
    }

    /** 指标标签用小写取值（低基数、稳定）。 */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
