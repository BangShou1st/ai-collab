package com.shitulelv.aicollab.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * P10-SCORER-ROBUSTNESS-01 增强拒答检测器（test scope，纯确定性）。
 *
 * <p>核心目标：
 * 1. 冻结题集的 abstentionPhrases 保持原样不修改，保留原始规则命中追溯（frozenPhraseHit）；
 * 2. 识别合理的未知状态与拒答扩展表达（如“尚未确定”、“并未明确”、“资料中未提及”等）；
 * 3. 区分“诚实拒答”、“限定条件的未知状态”、“回避但编造”、“未拒答直接编造”与“歧义待复核”；
 * 4. 防范仅靠出现拒答词就掩盖编造断言的投机行为。</p>
 */
public final class P10AbstentionDetector {

    public static final String RULE_VERSION = "2.0-scoped-contextual-v4";

    public enum AbstentionVerdict {
        /** 明确拒答或明确承认资料不足。 */
        HONEST_ABSTENTION,
        /** 有限定条件的未知状态（如“最终比例尚未确定，待管理层审批后通知”）。 */
        QUALIFIED_UNKNOWN,
        /** 回避问题或包含拒答措辞，但仍做出了未经支持的肯定断言/编造。 */
        CONFLICTING_FABRICATION,
        /** 没有拒答且直接给出未经支持的回答。 */
        MISSING_UNQUALIFIED,
        /** 语境不明确，机械规则无法确认，需人工复核。 */
        AMBIGUOUS_NEEDS_REVIEW,
        /** 非拒答题，不适用。 */
        NOT_APPLICABLE
    }

    public record AbstentionEvaluation(
            boolean abstainQuestion,
            boolean frozenPhraseHit,
            AbstentionVerdict verdict,
            List<String> matchedPhrases,
            String reason,
            String ruleVersion
    ) {}

    // 扩展的强拒答词典
    private static final List<String> EXTENDED_REFUSAL_PHRASES = List.of(
            "当前项目资料不足以回答", "资料不足", "材料不足", "现有材料无法确认", "现有资料无法确认",
            "无法确认", "不能确认", "未提供", "没有说明", "未载明", "未提及", "文档未载明",
            "无相关记录", "未予说明", "未涉及", "未作说明", "暂无相关资料", "没有提及"
    );

    // 扩展的限定未知状态词典
    private static final List<String> EXTENDED_UNKNOWN_PHRASES = List.of(
            "尚未确定", "并未明确", "未明确", "暂未确定", "待审批后另行通知", "待管理层审批",
            "尚无明确规定", "具体数量未载明", "具体份数未载明", "尚无法确定"
    );

    private P10AbstentionDetector() {
    }

    /**
     * 评估回答的拒答表现。
     *
     * @param abstainQuestion 是否为无答案题
     * @param frozenPhrases 题集冻结的拒答短语
     * @param normalizedAnswer 规范化后的回答文本
     * @param unsupportedNumbers 发现的来源中不存在的数字候选
     * @param affirmativeViolations 已发现的肯定违规列表
     */
    public static AbstentionEvaluation evaluate(
            boolean abstainQuestion,
            List<String> frozenPhrases,
            String normalizedAnswer,
            List<String> unsupportedNumbers,
            List<String> affirmativeViolations) {
        return evaluate(abstainQuestion, frozenPhrases, normalizedAnswer, unsupportedNumbers, affirmativeViolations, List.of());
    }

    public static AbstentionEvaluation evaluate(
            boolean abstainQuestion,
            List<String> frozenPhrases,
            String normalizedAnswer,
            List<String> unsupportedNumbers,
            List<String> affirmativeViolations,
            List<String> unresolvedAmbiguousForbidden) {

        if (!abstainQuestion) {
            return new AbstentionEvaluation(
                    false,
                    false,
                    AbstentionVerdict.NOT_APPLICABLE,
                    List.of(),
                    "非无答案题，不适用拒答检测",
                    RULE_VERSION
            );
        }

        // 1. 冻结规则的字面命中追溯（保持历史不可变性）
        boolean frozenHit = frozenPhrases != null && frozenPhrases.stream()
                .anyMatch(phrase -> P10TextNormalizer.containsFact(normalizedAnswer, phrase));

        // 2. 收集命中的扩展词汇
        List<String> matchedPhrases = new ArrayList<>();
        if (frozenPhrases != null) {
            for (String p : frozenPhrases) {
                if (P10TextNormalizer.containsFact(normalizedAnswer, p) && !matchedPhrases.contains(p)) {
                    matchedPhrases.add(p);
                }
            }
        }
        for (String p : EXTENDED_REFUSAL_PHRASES) {
            if (P10TextNormalizer.containsFact(normalizedAnswer, p) && !matchedPhrases.contains(p)) {
                matchedPhrases.add(p);
            }
        }
        for (String p : EXTENDED_UNKNOWN_PHRASES) {
            if (P10TextNormalizer.containsFact(normalizedAnswer, p) && !matchedPhrases.contains(p)) {
                matchedPhrases.add(p);
            }
        }

        boolean hasAnyRefusalMention = !matchedPhrases.isEmpty();

        // 3. 编造冲突检测：回答虽有拒答字眼，但仍断言了肯定违规（如编造的数据库名称或伪造的具体抽样数据）
        boolean hasFabrication = (affirmativeViolations != null && !affirmativeViolations.isEmpty())
                || (unsupportedNumbers != null && !unsupportedNumbers.isEmpty());

        if (hasAnyRefusalMention && hasFabrication) {
            return new AbstentionEvaluation(
                    true,
                    frozenHit,
                    AbstentionVerdict.CONFLICTING_FABRICATION,
                    matchedPhrases,
                    "回答虽然出现拒答/未知措辞，但同时给出了未经支持的编造断言或数字：" +
                            (affirmativeViolations != null && !affirmativeViolations.isEmpty() ? affirmativeViolations : unsupportedNumbers),
                    RULE_VERSION
            );
        }

        // 防御性安全加固：回答包含拒答措辞，但同时存在未澄清的禁断 marker（歧义/复核），不得直接认定为 HONEST_ABSTENTION
        boolean hasUnresolvedForbidden = unresolvedAmbiguousForbidden != null && !unresolvedAmbiguousForbidden.isEmpty();
        if (hasAnyRefusalMention && hasUnresolvedForbidden) {
            return new AbstentionEvaluation(
                    true,
                    frozenHit,
                    AbstentionVerdict.AMBIGUOUS_NEEDS_REVIEW,
                    matchedPhrases,
                    "回答虽包含拒答措辞，但同时存在处于复杂语境中未澄清的禁断表述，存在潜在冲突编造嫌疑，转人工复核：" + unresolvedAmbiguousForbidden,
                    RULE_VERSION
            );
        }

        if (hasFabrication && !hasAnyRefusalMention) {
            return new AbstentionEvaluation(
                    true,
                    frozenHit,
                    AbstentionVerdict.MISSING_UNQUALIFIED,
                    matchedPhrases,
                    "无答案题未做任何拒答或未知表述，直接断言了编造内容",
                    RULE_VERSION
            );
        }

        // 4. 强拒答检测
        boolean strongRefusalHit = EXTENDED_REFUSAL_PHRASES.stream()
                .anyMatch(p -> P10TextNormalizer.containsFact(normalizedAnswer, p));
        if (strongRefusalHit || frozenHit) {
            return new AbstentionEvaluation(
                    true,
                    frozenHit,
                    AbstentionVerdict.HONEST_ABSTENTION,
                    matchedPhrases,
                    "明确诚实拒答或承认资料不足，且未发现冲突编造",
                    RULE_VERSION
            );
        }

        // 5. 限定未知状态检测（如 AQ12: "尚未确定" / "资料中并未明确具体每年抽查多少份合同"）
        boolean unknownHit = EXTENDED_UNKNOWN_PHRASES.stream()
                .anyMatch(p -> P10TextNormalizer.containsFact(normalizedAnswer, p));
        if (unknownHit) {
            return new AbstentionEvaluation(
                    true,
                    frozenHit,
                    AbstentionVerdict.QUALIFIED_UNKNOWN,
                    matchedPhrases,
                    "回答指出了资料未明确或事项尚未确定的未知状态，符合保真原则",
                    RULE_VERSION
            );
        }

        // 6. 既无拒答词也未编造数字，但可能避而不答或文本歧义
        if (normalizedAnswer.length() < 30) {
            return new AbstentionEvaluation(
                    true,
                    frozenHit,
                    AbstentionVerdict.AMBIGUOUS_NEEDS_REVIEW,
                    matchedPhrases,
                    "回答未匹配标准拒答短语，且长度较短，无法可靠机械判定是否已诚实拒答，转人工复核",
                    RULE_VERSION
            );
        }

        // 7. 默认未诚实拒答
        return new AbstentionEvaluation(
            true,
            frozenHit,
            AbstentionVerdict.MISSING_UNQUALIFIED,
            matchedPhrases,
            "回答未包含任何已知拒答或未知状态措辞",
            RULE_VERSION
        );
    }
}
