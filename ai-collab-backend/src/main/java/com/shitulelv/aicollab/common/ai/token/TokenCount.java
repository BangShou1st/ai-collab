package com.shitulelv.aicollab.common.ai.token;

/**
 * 一次 token 计量的结果：数值 + 来源 + 依据 + 是否为估算。
 *
 * <p>不变量：{@link TokenCountSource#ESTIMATE} 与 {@link TokenCountSource#UNKNOWN}
 * 必须标记 estimated=true，绝不能伪装成精确值；PROVIDER/TOKENIZER 才是非估算来源。
 * 提供商上报的 usage 由 Provider 响应进入结算，不从本类型的工厂"伪造"。</p>
 *
 * @param count     非负 token 数
 * @param source    计量来源
 * @param basis     计量依据（如 CJK_WEIGHTED_V1 / MESSAGE_PROTOCOL）
 * @param estimated 是否估算（ESTIMATE/UNKNOWN 恒为 true）
 */
public record TokenCount(
        int count,
        TokenCountSource source,
        String basis,
        boolean estimated) {

    public TokenCount {
        count = Math.max(0, count);
        if (source == null) source = TokenCountSource.UNKNOWN;
        if (basis == null || basis.isBlank()) basis = source.name();
        estimated = source == TokenCountSource.ESTIMATE || source == TokenCountSource.UNKNOWN;
    }

    /** 提供商上报的真实用量（权威来源，非估算）。 */
    public static TokenCount provider(int count, String basis) {
        return new TokenCount(count, TokenCountSource.PROVIDER, basis, false);
    }

    /** 完整真实词表 tokenizer 的精确结果（非估算）。 */
    public static TokenCount tokenizer(int count, String basis) {
        return new TokenCount(count, TokenCountSource.TOKENIZER, basis, false);
    }

    /** CJK 感知系数估算。 */
    public static TokenCount estimate(int count, String basis) {
        return new TokenCount(count, TokenCountSource.ESTIMATE, basis, true);
    }

    /** 无任何可用证据的未知计量。 */
    public static TokenCount unknown(int count, String basis) {
        return new TokenCount(count, TokenCountSource.UNKNOWN, basis, true);
    }

    /** 空输入：没有 token 需要计量，来源明确为未知而不是"精确 0"。 */
    public static TokenCount zero() {
        return new TokenCount(0, TokenCountSource.UNKNOWN, "EMPTY", true);
    }

    /**
     * 合并两次计量：来源按最保守口径收敛。
     * UNKNOWN 优先；仅当双方都是 PROVIDER 时才保持 PROVIDER；仅当双方都是
     * TOKENIZER 时才保持 TOKENIZER；其余混合情况一律降为 ESTIMATE。
     *
     * <p>空输入（{@link #zero()}，basis=EMPTY）是加法的中性元素：空消息/空文本
     * 不得把一批已有 ESTIMATE/PROVIDER 证据的合计污染成 UNKNOWN。</p>
     */
    public TokenCount add(TokenCount other) {
        if (other == null || other.isEmpty()) return this;
        if (isEmpty()) return other;
        TokenCountSource finalSource;
        if (this.source == TokenCountSource.UNKNOWN || other.source == TokenCountSource.UNKNOWN) {
            finalSource = TokenCountSource.UNKNOWN;
        } else if (this.source == TokenCountSource.PROVIDER && other.source == TokenCountSource.PROVIDER) {
            finalSource = TokenCountSource.PROVIDER;
        } else if (this.source == TokenCountSource.TOKENIZER && other.source == TokenCountSource.TOKENIZER) {
            finalSource = TokenCountSource.TOKENIZER;
        } else {
            finalSource = TokenCountSource.ESTIMATE;
        }
        String combinedBasis = this.basis + "+" + other.basis;
        return new TokenCount(this.count + other.count, finalSource, combinedBasis,
                finalSource == TokenCountSource.ESTIMATE || finalSource == TokenCountSource.UNKNOWN);
    }

    /** 空输入计量：count=0 且 basis=EMPTY。仅作为加法中性元素，不参与来源收敛。 */
    private boolean isEmpty() {
        return count == 0 && "EMPTY".equals(basis);
    }
}
