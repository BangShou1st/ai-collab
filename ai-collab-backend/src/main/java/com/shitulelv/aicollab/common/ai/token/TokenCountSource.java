package com.shitulelv.aicollab.common.ai.token;

/**
 * Token 计量的来源（J0-1 统一口径）。
 *
 * <p>来源必须显式表达，估算值不得冒充精确值：</p>
 * <ul>
 *   <li>{@link #PROVIDER}：提供商响应上报的真实 usage（唯一权威来源）；</li>
 *   <li>{@link #TOKENIZER}：具备完整真实词表的精确 tokenizer；</li>
 *   <li>{@link #ESTIMATE}：按固定系数的 CJK 感知估算；</li>
 *   <li>{@link #UNKNOWN}：没有任何可用证据。</li>
 * </ul>
 */
public enum TokenCountSource {
    PROVIDER,
    TOKENIZER,
    ESTIMATE,
    UNKNOWN
}
