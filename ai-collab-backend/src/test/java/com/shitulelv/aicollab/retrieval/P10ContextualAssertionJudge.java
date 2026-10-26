package com.shitulelv.aicollab.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P10-SCORER-ROBUSTNESS-01 上下文肯定性判定器（test scope，纯确定性）。
 *
 * <p>核心目标：区分肯定断言（AFFIRMATIVE_VIOLATION）、否定或认识模态提及（NEGATED_OR_EPISTEMIC_MENTION）
 * 以及无法可靠判定的歧义/复杂语境（AMBIGUOUS_NEEDS_REVIEW）。
 * 只有具备确凿机械证据的肯定断言才产生高严重度违规信号，避免对否定或“资料未明确”等诚实回答产生假阳性误判。</p>
 */
public final class P10ContextualAssertionJudge {

    public static final String RULE_VERSION = "2.0-contextual";

    public enum AssertionVerdict {
        /** 有充分机械证据表明回答肯定了被禁止的命题（生成高严重度违规）。 */
        AFFIRMATIVE_VIOLATION,
        /** 命题处于明确否定、未知状态或资料不足的语境（不产生违规告警）。 */
        NEGATED_OR_EPISTEMIC_MENTION,
        /** 机械规则无法可靠判定语义（双重否定、复杂跨句、例外重叠等，进入人工复核）。 */
        AMBIGUOUS_NEEDS_REVIEW
    }

    public record Assessment(
            String marker,
            AssertionVerdict verdict,
            String sentence,
            String reason,
            String ruleVersion
    ) {}

    // 分句正则（保留标点）
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[^。！？；\\n]+[。！？；\\n]?");

    // 强双重否定模式（语义复杂，反转概率极高，必须人工复核）
    private static final Pattern DOUBLE_NEGATION = Pattern.compile(
            "并非不|并不是不|绝非不|并非没有|不能不|不得不|不可不|未尝不|非无|难以否定|并非未");

    // 假设/反问/复杂转折模式（必须人工复核）
    private static final Pattern RHETORICAL_OR_HYPOTHETICAL = Pattern.compile(
            "难道|岂能|假若|若是.*则|尽管.*但|虽.*但.*可以|理论上.*实际|如果.*可以.*吗");

    // 认识模态短语（未知、未明确、资料不足）
    private static final Pattern EPISTEMIC_UNKNOWN = Pattern.compile(
            "并未明确|未明确|尚未确定|尚未明确|未提及|未载明|没有明确|没有说明|没有载明|" +
            "未作规定|未有规定|无法确认|不能确认|资料不足|材料不足|当前资料不足|现有资料无法|不知|未知|未载|没有给出");

    // 前置直接否定词与助词（紧邻修饰动词命题）
    private static final Pattern DIRECT_NEGATION_PREFIX = Pattern.compile(
            "(?:通常|一般|原则上|一律|均)?(?:不|未|不得|不能|禁止|严禁|切勿|不允许|并未|绝不)$");

    // 疑问/未知指示词（常跟在认知模态后修饰抽查、数量、标准等）
    private static final Pattern UNKNOWN_INDICATOR = Pattern.compile(
            "多少|何种|具体|是否|比例|数量|范围|何时|哪|怎样");

    // 例外条款引导词
    private static final Pattern EXCEPTION_MARKER = Pattern.compile(
            "除非|特批|例外|特殊情况|经.*批准后方可|除.*外");

    private P10ContextualAssertionJudge() {
    }

    /**
     * 对整个回答文本中的禁断短语进行上下文判定。
     */
    public static List<Assessment> evaluateForbiddenAffirmations(
            String normalizedAnswer, List<String> forbiddenMarkers) {
        List<Assessment> assessments = new ArrayList<>();
        if (normalizedAnswer == null || normalizedAnswer.isBlank() || forbiddenMarkers == null || forbiddenMarkers.isEmpty()) {
            return assessments;
        }

        List<String> sentences = extractSentences(normalizedAnswer);

        for (String rawMarker : forbiddenMarkers) {
            String marker = P10TextNormalizer.normalizeMarker(rawMarker);
            if (marker.isBlank()) continue;

            for (String sentence : sentences) {
                if (P10TextNormalizer.containsFact(sentence, marker)) {
                    Assessment assessment = judgeSentence(sentence, marker);
                    assessments.add(assessment);
                }
            }
        }
        return assessments;
    }

    /**
     * 切分句子，保留完整分句内容。
     */
    public static List<String> extractSentences(String text) {
        List<String> list = new ArrayList<>();
        Matcher matcher = SENTENCE_SPLIT.matcher(text);
        while (matcher.find()) {
            String s = matcher.group().strip();
            if (!s.isBlank()) {
                list.add(s);
            }
        }
        return list;
    }

    /**
     * 对单句中命中的 marker 进行上下文语义判定。
     */
    public static Assessment judgeSentence(String sentence, String marker) {
        String cleanSentence = sentence.strip();

        // 1. 双重否定检测 -> 转向人工复核
        if (DOUBLE_NEGATION.matcher(cleanSentence).find()) {
            return new Assessment(
                    marker,
                    AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW,
                    cleanSentence,
                    "检测到双重否定结构，语义可能翻转，机械规则无法可靠保证，转人工复核",
                    RULE_VERSION
            );
        }

        // 2. 假设/反问/复杂转折检测 -> 转向人工复核
        if (RHETORICAL_OR_HYPOTHETICAL.matcher(cleanSentence).find()) {
            return new Assessment(
                    marker,
                    AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW,
                    cleanSentence,
                    "检测到假设、反问或复杂转折句型，进入人工复核",
                    RULE_VERSION
            );
        }

        int markerIndex = cleanSentence.indexOf(marker);
        String prefix = markerIndex > 0 ? cleanSentence.substring(0, markerIndex).strip() : "";
        String suffix = markerIndex >= 0 && markerIndex + marker.length() < cleanSentence.length()
                ? cleanSentence.substring(markerIndex + marker.length()).strip() : "";

        // 3. 认识模态与未知状态表达（如 AQ12: "资料中并未明确具体每年抽查多少份合同"）
        if (EPISTEMIC_UNKNOWN.matcher(cleanSentence).find()) {
            // 如果句子中包含认识模态短语，且出现在 marker 之前或带有疑问/未知指示词
            if (EPISTEMIC_UNKNOWN.matcher(prefix).find() || UNKNOWN_INDICATOR.matcher(suffix).find()
                    || cleanSentence.contains("并未明确") || cleanSentence.contains("尚未确定") || cleanSentence.contains("资料不足")) {
                return new Assessment(
                        marker,
                        AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                        cleanSentence,
                        "命题处于明确的资料未明确/尚未确定等认识模态语境中，非肯定断言",
                        RULE_VERSION
                );
            }
        }

        // 4. 前置动词翻转否定（如 AQ06: "通常不可以先组织培训再补签合同"）
        // 如果 marker 以 "可以" 或 "允许" 开头，或者 prefix 结尾为否定副词/助动词
        if (marker.startsWith("可以") || marker.startsWith("允许")) {
            if (DIRECT_NEGATION_PREFIX.matcher(prefix).find()) {
                return new Assessment(
                        marker,
                        AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                        cleanSentence,
                        "动词短语前存在直接否定修饰（如'不可以'、'不得'、'通常不'），构成命题否定",
                        RULE_VERSION
                );
            }
        }

        // 5. 前缀中紧邻否定词（距离 marker 小于等于 6 个字符的片段）
        if (!prefix.isEmpty()) {
            String immediatePrefix = prefix.length() <= 8 ? prefix : prefix.substring(prefix.length() - 8);
            if (DIRECT_NEGATION_PREFIX.matcher(immediatePrefix).find()) {
                return new Assessment(
                        marker,
                        AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                        cleanSentence,
                        "命题紧邻前置否定词修饰，属于否定陈述",
                        RULE_VERSION
                );
            }
        }

        // 6. 例外条款分析
        if (EXCEPTION_MARKER.matcher(cleanSentence).find()) {
            // 如果句子中同时有否定词（如“除非...不得...”），说明是带例外的禁止性条款，非肯定违规
            if (cleanSentence.contains("不得") || cleanSentence.contains("不能") || cleanSentence.contains("不可以") || cleanSentence.contains("禁止")) {
                return new Assessment(
                        marker,
                        AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                        cleanSentence,
                        "句子陈述制度例外条款与禁止性主干（如'除非...不得...'），非肯定违规",
                        RULE_VERSION
                );
            } else {
                // 如果只出现例外词而无明确否定主干，无法机械判定例外是否越界，转人工复核
                return new Assessment(
                        marker,
                        AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW,
                        cleanSentence,
                        "句子包含例外条款但结构特殊，需人工复核是否合规",
                        RULE_VERSION
                );
            }
        }

        // 7. 经过以上严格排查后，无任何否定/认知修饰，直接断言该命题 -> 肯定违规
        return new Assessment(
                marker,
                AssertionVerdict.AFFIRMATIVE_VIOLATION,
                cleanSentence,
                "机械证据确凿：句子在无否定、无未知模态修饰的情况下肯定断言了禁断命题",
                RULE_VERSION
        );
    }
}
