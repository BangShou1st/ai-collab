package com.shitulelv.aicollab.agent.domain.model;

/**
 * CJK 感知的 token 纯估算器（预算治理 v3，设计文档 §4.3）。
 *
 * <p>背景：原 {@code codePoint/3} 与 {@code chars/3} 估算对中文系统性低估约 2 倍
 * （现代 tokenizer 对中文约 0.6-1 token/字，而 1/3 ≈ 0.33），导致估算型预算
 * （软阈值触发时机、会话预估）偏晚。本估算器按 codepoint 分类折算：</p>
 *
 * <pre>tokens ≈ ascii/4 + cjk×0.75 + other×0.5</pre>
 *
 * <p>纯函数、无状态、无外部依赖；不接入真实 tokenizer 库——provider 上报
 * （PROVIDER basis）优先的既有语义不变，本估算器只承担 fallback 估算，
 * 系数法可测试、可解释（见 {@code TokenEstimatorTest} 的样本回归）。</p>
 */
public final class TokenEstimator {

    /**
     * ASCII 可打印字符与空白符的折算系数：约 4 字符折 1 token。
     * 依据：现代 BPE tokenizer 对常规英文文本的经验值约 0.2-0.3 token/字符，
     * 4 字符/token 是通用近似基准。
     */
    private static final double TOKENS_PER_ASCII_CHAR = 0.25;

    /**
     * CJK 统一表意文字（含扩展 A/B 区）的折算系数：0.75 token/字。
     * 依据：现代 tokenizer 对中文的实测区间约 0.6-1 token/字，取中值；
     * 由单元测试的样本回归钉住（纯中文每字 0.6-0.9）。
     */
    private static final double TOKENS_PER_CJK_CHAR = 0.75;

    /**
     * 其余 codepoint（emoji、全角标点、其他文字系统、控制字符等）的折算系数：
     * 0.5 token/字符，介于 ASCII 与 CJK 之间的保守取值。
     */
    private static final double TOKENS_PER_OTHER_CHAR = 0.5;

    private TokenEstimator() {
    }

    /**
     * 估算文本的 token 数。null 或空串返回 0；非空文本按 codepoint 分类累加后
     * 四舍五入到整数。
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        double total = 0;
        int index = 0;
        int length = text.length();
        while (index < length) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                total += TOKENS_PER_CJK_CHAR;
            } else if (isAsciiText(codePoint)) {
                total += TOKENS_PER_ASCII_CHAR;
            } else {
                total += TOKENS_PER_OTHER_CHAR;
            }
        }
        return (int) Math.round(total);
    }

    /** ASCII 可打印字符（0x20-0x7E）与 ASCII 空白符（\t \n \x0B \f \r）。 */
    private static boolean isAsciiText(int codePoint) {
        return (codePoint >= 0x20 && codePoint < 0x7F) || (codePoint >= 0x09 && codePoint <= 0x0D);
    }

    /**
     * CJK 统一表意文字及扩展 A/B 区（按 {@link Character.UnicodeBlock} 判断，
     * 兼容 BMP 之外的扩展 B 区增补平面字符）。
     */
    private static boolean isCjk(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == null) return false;
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B;
    }
}
