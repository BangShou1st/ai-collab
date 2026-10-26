package com.shitulelv.aicollab.retrieval;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P10-SCORER-ROBUSTNESS-01 文本规范化工具（test scope，纯确定性）。
 *
 * <p>核心目标：消除 Markdown 强调、非语义空白和字符编码差异对语义评判的机械干扰，
 * 使得“通常**不可以**先组织培训再补签合同”与“通常不可以先组织培训再补签合同”得到一致的语义表示，
 * 同时严格保留实际词语、否定词、例外词、引用标记（如 [S1]）和分句边界。</p>
 */
public final class P10TextNormalizer {

    // 匹配成对的 Markdown 强调标记：***text***, **text**, *text*, ___text___, __text__, _text_, `code`
    // 注意：不破坏 [S1] 等方括号引用，不破坏中文字符与标点
    private static final Pattern BOLD_ITALIC_STAR = Pattern.compile("\\*{3}([^*\\n]+)\\*{3}");
    private static final Pattern BOLD_STAR = Pattern.compile("\\*{2}([^*\\n]+)\\*{2}");
    private static final Pattern ITALIC_STAR = Pattern.compile("\\*([^*\\n]+)\\*");
    private static final Pattern BOLD_ITALIC_UNDER = Pattern.compile("_{3}([^_\\n]+)_{3}");
    private static final Pattern BOLD_UNDER = Pattern.compile("_{2}([^_\\n]+)_{2}");
    private static final Pattern ITALIC_UNDER = Pattern.compile("(?<![a-zA-Z0-9])_([^_\\n]+)_(?![a-zA-Z0-9])");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");

    // 连续空白字符（非换行空白：空格、Tab、全角空格、不换行空格、零宽空格等）
    private static final Pattern HORIZONTAL_WHITESPACE = Pattern.compile("[\\t\\x0B\\f\\r\\u00A0\\u200B\\u3000 ]+");
    // 多重换行规范化为单换行
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\\n\\s*\\n+");

    private P10TextNormalizer() {
    }

    /**
     * 剥离 Markdown 强调标记（粗体、斜体、行内代码），保留词语本身。
     */
    public static String stripMarkdownFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = text;
        // 递归/多轮剥离嵌套强调
        s = BOLD_ITALIC_STAR.matcher(s).replaceAll("$1");
        s = BOLD_ITALIC_UNDER.matcher(s).replaceAll("$1");
        s = BOLD_STAR.matcher(s).replaceAll("$1");
        s = BOLD_UNDER.matcher(s).replaceAll("$1");
        s = ITALIC_STAR.matcher(s).replaceAll("$1");
        s = ITALIC_UNDER.matcher(s).replaceAll("$1");
        s = INLINE_CODE.matcher(s).replaceAll("$1");
        return s;
    }

    /**
     * 统一 Unicode（NFKC 形式），将全角字母数字、兼容字符转换为标准形式。
     */
    public static String normalizeUnicode(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    /**
     * 对回答文本或长段落进行基础规范化：
     * 1. 剥离 Markdown 强调（**bold**, *italic*, `code`）；
     * 2. Unicode NFKC 规范化；
     * 3. 压缩水平连续空白；
     * 4. 压缩过多的连续换行，保留分句换行；
     * 5. 首尾 trim。
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String stripped = stripMarkdownFormatting(text);
        String nfkc = normalizeUnicode(stripped);
        String singleSpaced = HORIZONTAL_WHITESPACE.matcher(nfkc).replaceAll(" ");
        String cleanLines = MULTIPLE_NEWLINES.matcher(singleSpaced).replaceAll("\n");
        return cleanLines.strip();
    }

    /**
     * 对待匹配的 Fact Marker 进行规范化（与回答文本使用完全一致的规范化管道）。
     */
    public static String normalizeMarker(String marker) {
        return normalize(marker);
    }

    /**
     * 在回答文本中匹配 marker：
     * 既支持规范化后的直接包含，也支持忽略非语义性换行/空格插入的连续词匹配。
     * 保证：若原意实际缺失，绝不制造匹配；若仅是空格/换行/Markdown强调差异，稳定命中。
     */
    public static boolean containsFact(String normalizedAnswer, String normalizedMarker) {
        if (normalizedAnswer == null || normalizedMarker == null || normalizedMarker.isEmpty()) {
            return false;
        }
        if (normalizedAnswer.contains(normalizedMarker)) {
            return true;
        }
        // 如果 marker 内部包含空格，或者回答在相应位置存在换行，测试紧凑对比
        String compactAnswer = normalizedAnswer.replaceAll("\\s+", "");
        String compactMarker = normalizedMarker.replaceAll("\\s+", "");
        return !compactMarker.isEmpty() && compactAnswer.contains(compactMarker);
    }
}
