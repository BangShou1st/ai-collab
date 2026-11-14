package com.shitulelv.aicollab.retrieval.rerank;

import java.util.Locale;

/**
 * 重排模式（W3 冻结契约 §3）：决定 Java 检索管线把重排工作交给谁。
 *
 * <ul>
 *   <li>{@link #JAVA}（默认）：完全委托当前 active reranker，逐分数/逐顺序等价，
 *       <b>不产生任何 Python 调用、也不记录调用/延迟指标</b>；</li>
 *   <li>{@link #SHADOW}：返回 Java 结果，Python 结果只用于离线对比指标，且异步执行，
 *       绝不进入用户请求关键路径；</li>
 *   <li>{@link #PYTHON}：使用 Python 分数，失败/超时/非法响应时回退 Java 启发式分数，
 *       请求本身绝不失败。</li>
 * </ul>
 *
 * <p>取值大小写不敏感（{@code Java}、{@code SHADOW} 均可）。未知值必须让应用
 * <b>启动失败</b>，不得静默回退到默认值——静默回退会把"配置写错"伪装成"灰度未生效"，
 * 是最难排查的一类事故。</p>
 */
public enum RerankMode {

    JAVA,
    SHADOW,
    PYTHON;

    /**
     * 解析配置值。
     *
     * @param raw 原始配置值（可为 null）
     * @return 对应模式
     * @throws IllegalStateException 值为空或不在允许集合内（启动失败）
     */
    public static RerankMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("rerank.mode 不能为空，允许取值：java | shadow | python");
        }
        String normalized = raw.trim();
        for (RerankMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "未知的 rerank.mode：" + normalized + "，允许取值：java | shadow | python");
    }

    /** 指标标签用小写取值（低基数、稳定）。 */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
