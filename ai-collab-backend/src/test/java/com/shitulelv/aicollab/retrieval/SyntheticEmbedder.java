package com.shitulelv.aicollab.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * B 部分专用的<b>合成确定性嵌入函数</b>：把文本切成词元，哈希到固定维度的桶向量后做 L2 归一化。
 *
 * <p>刻意不使用任何真实模型：本批基线要观察的是 pgvector 排序、指纹/代际过滤、文档范围限定
 * 与上下文保留，而不是模型语义质量。余弦相似度在这里等于词元重合度，
 * 因此“哪条证据应该排前面”是<b>可推导、可复现</b>的，不含模型随机性。</p>
 *
 * <p>这与生产嵌入网关不是同一实现——这里得到的一切数字都不能当成模型召回质量。
 * 真实模型基线由既有的 {@code RealRetrievalBaselineTest}（AI_REAL_RETRIEVAL 门控）负责。</p>
 */
final class SyntheticEmbedder {
    static final int DIMENSIONS = 128;

    private SyntheticEmbedder() {
    }

    static List<Double> embed(String text) {
        double[] vector = new double[DIMENSIONS];
        for (String token : tokens(text)) {
            vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1.0d;
        }
        double norm = 0d;
        for (double value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        List<Double> normalized = new ArrayList<>(DIMENSIONS);
        for (double value : vector) {
            normalized.add(norm == 0d ? 0d : round(value / norm));
        }
        return normalized;
    }

    /** pgvector 需要字面量形式（如 {@code [0.1,0.2]}）。 */
    static String literal(String text) {
        StringBuilder builder = new StringBuilder("[");
        List<Double> vector = embed(text);
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(vector.get(i));
        }
        return builder.append(']').toString();
    }

    /**
     * 中英文都能切：拉丁/数字连续段作为一个词元（小写），
     * 中日韩等表意文字逐字成词元并追加相邻二元组（提高区分度），其余字符作分隔。
     */
    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        String previousIdeograph = null;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint) && codePoint < 128) {
                latin.appendCodePoint(Character.toLowerCase(codePoint));
                // 任何非表意字符都必须打断相邻关系，否则 "事1实" 会生成并不相邻的二元组 "事实"。
                previousIdeograph = null;
                continue;
            }
            if (latin.length() > 0) {
                tokens.add(latin.toString());
                latin.setLength(0);
            }
            if (Character.isLetter(codePoint)) {
                String ideograph = new String(Character.toChars(codePoint));
                tokens.add(ideograph);
                if (previousIdeograph != null) tokens.add(previousIdeograph + ideograph);
                previousIdeograph = ideograph;
            } else {
                previousIdeograph = null;
            }
        }
        if (latin.length() > 0) tokens.add(latin.toString());
        return tokens;
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
