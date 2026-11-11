package com.shitulelv.aicollab.agent.infrastructure.decide;

import java.util.Locale;

/**
 * Agent decide 模式（W4 冻结契约 §3）：决定 Java 是否把工具轮决策交给 Python worker。
 *
 * <ul>
 *   <li>{@link #JAVA}（默认）：零外呼、零快照、零指标，行为与未实现时完全一致；</li>
 *   <li>{@link #SHADOW}：只在满足全部条件的工具轮边界发起后台调用，任何失败只记指标；</li>
 *   <li>{@link #PYTHON}：W4 未实现，<b>配置即启动失败</b>，防止"以为切了"。</li>
 * </ul>
 *
 * <p>取值大小写不敏感。未知值与 {@code python} 都必须让应用启动失败，
 * 不得静默回退到默认值——静默回退会把"配置写错"伪装成"灰度未生效"。</p>
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
     * @throws IllegalStateException 值为空、未知或为尚未实现的 {@code python}（启动失败）
     */
    public static DecideMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "decide.mode 不能为空，允许取值：java | shadow（python 模式 W4 未实现）");
        }
        String normalized = raw.trim();
        for (DecideMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                if (mode == PYTHON) {
                    throw new IllegalStateException(
                            "decide.mode=python 模式 W4 未实现，拒绝启动；允许取值：java | shadow");
                }
                return mode;
            }
        }
        throw new IllegalStateException(
                "未知的 decide.mode：" + normalized
                        + "，允许取值：java | shadow（python 模式 W4 未实现）");
    }

    /** 指标标签用小写取值（低基数、稳定）。 */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
