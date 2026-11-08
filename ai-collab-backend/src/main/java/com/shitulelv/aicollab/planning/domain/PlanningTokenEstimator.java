package com.shitulelv.aicollab.planning.domain;

/**
 * CJK 感知的 token 纯估算器（planning 包内等价实现）。
 *
 * <p><b>来源说明</b>：本类是 {@code agent.domain.model.TokenEstimator}（预算治理 v3，
 * 设计文档 §4.3）的等价复制。不直接 import 的原因：agent 模块已依赖 planning 模块
 * （AgentPlanningOperationService / PlanningAgentTools / AgentContextAssembler），
 * 引入 planning→agent 依赖会形成双向模块依赖，不符合现有分层惯例；
 * 因此按任务约定在 planning 包内放置等价纯函数并注明来源。</p>
 *
 * <p>折算公式：{@code tokens ≈ ascii/4 + cjk×0.75 + other×0.5}。
 * 纯函数、无状态、无外部依赖；只承担估算型预算（动态批次切分）的 fallback 估算，
 * provider 上报（PROVIDER basis）优先的既有语义不变。</p>
 */
public final class PlanningTokenEstimator {

    /** ASCII 可打印字符与空白符：约 4 字符折 1 token（与来源实现一致）。 */
    private static final double TOKENS_PER_ASCII_CHAR = 0.25;

    /** CJK 统一表意文字（含扩展 A/B 区）：0.75 token/字（样本回归钉住 0.6-0.9）。 */
    private static final double TOKENS_PER_CJK_CHAR = 0.75;

    /** 其余 codepoint（emoji、全角标点、其他文字系统等）：0.5 token/字符。 */
    private static final double TOKENS_PER_OTHER_CHAR = 0.5;

    private PlanningTokenEstimator() {
    }

    /** 估算文本的 token 数。null 或空串返回 0；非空文本按 codepoint 分类累加后四舍五入。 */
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

    public static int estimate(Iterable<String> texts) {
        if (texts == null) return 0;
        int total = 0;
        for (String text : texts) total += estimate(text);
        return total;
    }

    /** ASCII 可打印字符（0x20-0x7E）与 ASCII 空白符（\t \n \x0B \f \r）。 */
    private static boolean isAsciiText(int codePoint) {
        return (codePoint >= 0x20 && codePoint < 0x7F) || (codePoint >= 0x09 && codePoint <= 0x0D);
    }

    /** CJK 统一表意文字及扩展 A/B 区（兼容增补平面字符）。 */
    private static boolean isCjk(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == null) return false;
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B;
    }
}
