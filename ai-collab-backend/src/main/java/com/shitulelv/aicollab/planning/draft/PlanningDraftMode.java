package com.shitulelv.aicollab.planning.draft;

import java.util.Locale;

/**
 * Planning draft 模式（W5 冻结 §2）。
 *
 * <ul>
 *   <li>{@link #JAVA}（默认）：零外呼、零快照、零指标，与未实现时逐字节一致；</li>
 *   <li>{@link #SHADOW}：在 Java 草稿提交前异步观察 Python 草稿，任何失败只记指标；</li>
 *   <li>{@link #PYTHON}：W5 未实现，<b>配置即启动失败</b>，防止“以为切了”。</li>
 * </ul>
 *
 * <p>取值大小写不敏感。未知值与 {@code python} 都必须让应用启动失败，不得静默回退——
 * 静默回退会把“配置写错”伪装成“灰度未生效”。</p>
 */
public enum PlanningDraftMode {

    JAVA,
    SHADOW,
    PYTHON;

    /**
     * 解析配置值。
     *
     * @throws IllegalStateException 值为空、未知或为尚未实现的 {@code python}（启动失败）
     */
    public static PlanningDraftMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "planning.draft.mode 不能为空，允许取值：java | shadow（python 模式 W5 未实现）");
        }
        String normalized = raw.trim();
        for (PlanningDraftMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                if (mode == PYTHON) {
                    throw new IllegalStateException(
                            "planning.draft.mode=python 模式 W5 未实现，拒绝启动；允许取值：java | shadow");
                }
                return mode;
            }
        }
        throw new IllegalStateException(
                "未知的 planning.draft.mode：" + normalized
                        + "，允许取值：java | shadow（python 模式 W5 未实现）");
    }

    /** 指标标签用小写取值（低基数、稳定）。 */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
