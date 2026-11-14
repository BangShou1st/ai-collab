package com.shitulelv.aicollab.planning.draft;

import java.util.Locale;

/**
 * Planning draft 模式（W5 冻结 §2）。
 *
 * <ul>
 *   <li>{@link #JAVA}（默认）：零外呼、零快照、零指标，与未实现时逐字节一致；</li>
 *   <li>{@link #SHADOW}：在 Java 草稿提交前异步观察 Python 草稿，任何失败只记指标；</li>
 *   <li>{@link #PYTHON}：由 {@code PlanningDraftRouter} 在 skeleton 阶段消费 Python 草稿；
 *       草稿必须重跑 Java Validator，失败回退 Java 两阶段生成。</li>
 * </ul>
 *
 * <p>取值大小写不敏感；未知值必须让应用启动失败，不得静默回退。</p>
 */
public enum PlanningDraftMode {

    JAVA,
    SHADOW,
    PYTHON;

    /**
     * 解析配置值。
     *
     * @throws IllegalStateException 值为空或未知（启动失败）
     */
    public static PlanningDraftMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "planning.draft.mode 不能为空，允许取值：java | shadow | python");
        }
        String normalized = raw.trim();
        for (PlanningDraftMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "未知的 planning.draft.mode：" + normalized
                        + "，允许取值：java | shadow | python");
    }

    /** 指标标签用小写取值（低基数、稳定）。 */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
