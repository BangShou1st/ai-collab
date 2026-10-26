package com.shitulelv.aicollab.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P10-SCORER-ROBUSTNESS-02 上下文肯定性判定器（test scope，纯确定性）。
 *
 * <p>核心目标：区分肯定断言（AFFIRMATIVE_VIOLATION）、明确否定或认识模态提及（NEGATED_OR_EPISTEMIC_MENTION）
 * 以及无法可靠判定的歧义/复杂语境（AMBIGUOUS_NEEDS_REVIEW）。
 *
 * <p>本阶段核心修复：
 * 1. 消除认识模态判断作用域过宽问题，不再对整句无条件放行；
 * 2. 隔离转折词（“但”、“但是”、“然而”、“实际上”等）前后的独立分句与命题主张，防止转折前后相互抑制；
 * 3. 支持同句中同一禁断 marker 多次出现（multiple occurrences）的逐个独立判定；
 * 4. 复杂转折反差语境（如“并未明确……但实际上可以……”）安全进入人工复核，绝不机械放行。</p>
 */
public final class P10ContextualAssertionJudge {

    public static final String RULE_VERSION = "2.0-scoped-contextual-v4";

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

    // 大句切分正则（保留大句标点）
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[^。！？；\\n]+[。！？；\\n]?");

    // 强双重否定模式（语义复杂，反转概率极高，必须人工复核）
    private static final Pattern DOUBLE_NEGATION = Pattern.compile(
            "并非不|并不是不|绝非不|并非没有|不能不|不得不|不可不|未尝不|非无|难以否定|并非未");

    // 假设/反问/复杂转折反差模式（无法机械确证，必须人工复核）
    private static final Pattern RHETORICAL_OR_HYPOTHETICAL = Pattern.compile(
            "难道|岂能|假若|若是.*则|尽管.*但|虽.*但.*可以|理论上.*实际|如果.*可以.*吗|" +
            "(?:并未明确|尚未确定|未明确|资料不足).*?(?:但实际上|但其实|实际上|其实).*?(?:可以|允许|可行)");

    // 认识模态短语（未知、未明确、资料不足）
    private static final Pattern EPISTEMIC_UNKNOWN = Pattern.compile(
            "并未明确|未明确|尚未确定|尚未明确|未提及|未载明|没有明确|没有说明|没有载明|" +
            "未作规定|未有规定|无法确认|不能确认|资料不足|材料不足|当前资料不足|现有资料无法|不知|未知|未载|没有给出");

    // 转折连词正则（用于分句隔离与作用域截断）
    private static final Pattern ADVERSATIVE_CONJUNCTION = Pattern.compile(
            "但(?:是)?|然而|不过|可是|却|实际上|其实|事实是|实质上|反之");

    // 前置直接否定词与助词（紧邻修饰动词命题）
    private static final Pattern DIRECT_NEGATION_PREFIX = Pattern.compile(
            "(?:通常|一般|原则上|一律|均)?(?:不|未|不得|不能|禁止|严禁|切勿|不允许|并未|绝不)$");

    // 后置否定谓语修饰（主语命题 + 紧邻后置否定动词/形容词）
    private static final Pattern SUFFIX_NEGATION = Pattern.compile(
            "^(?:是|为)?(?:不(?:被)?允许|禁止|严禁|不合规|违规|不适用|不被认可|不成立|不被采纳|不可行|不得行|不行)" +
            "(?:的|执行|操作|采用)?");

    // 主语代词/主体（用于识别新动作主干，防止认识模态错误跨界修饰）
    private static final Pattern SUBJECT_OR_AGENT = Pattern.compile(
            "我们|部门|各部门|项目组|团队|员工|人员|大家|经办人|各项目|管理层");

    // 疑问/未知指示词（常跟在认知模态后修饰抽查、数量、标准等）
    private static final Pattern UNKNOWN_INDICATOR = Pattern.compile(
            "多少|何种|具体|是否|比例|数量|范围|何时|哪|怎样");

    // 例外条款引导词
    private static final Pattern EXCEPTION_MARKER = Pattern.compile(
            "除非|特批|例外|特殊情况|经.*批准后方可|除.*外");

    private P10ContextualAssertionJudge() {
    }

    /**
     * 对整个回答文本中的禁断短语进行上下文判定（支持同句多 occurrence）。
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
                    List<Assessment> evaluated = evaluateOccurrences(sentence, marker);
                    assessments.addAll(evaluated);
                }
            }
        }
        return assessments;
    }

    /**
     * 切分大句，保留完整大句内容。
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
     * 对单句中命中的 marker 进行上下文语义判定（兼容入口，优先返回高严重度违规）。
     */
    public static Assessment judgeSentence(String sentence, String marker) {
        List<Assessment> assessments = evaluateOccurrences(sentence, marker);
        if (assessments.isEmpty()) {
            return new Assessment(marker, AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION, sentence, "未在句子中定位到有效标记", RULE_VERSION);
        }
        // 违规断言具有最高优先级，防止漏报
        for (Assessment a : assessments) {
            if (a.verdict() == AssertionVerdict.AFFIRMATIVE_VIOLATION) {
                return a;
            }
        }
        // 歧义/人工复核次高
        for (Assessment a : assessments) {
            if (a.verdict() == AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW) {
                return a;
            }
        }
        // 否则返回否定/认识模态
        return assessments.get(0);
    }

    /**
     * 对单个大句内 marker 的所有出现位置进行独立判定。
     */
    public static List<Assessment> evaluateOccurrences(String sentence, String marker) {
        List<Assessment> results = new ArrayList<>();
        String cleanSentence = sentence.strip();

        // 查找 marker 的所有出现位置
        List<Integer> occurrenceIndices = new ArrayList<>();
        int idx = 0;
        while ((idx = cleanSentence.indexOf(marker, idx)) != -1) {
            occurrenceIndices.add(idx);
            idx += marker.length();
        }

        if (occurrenceIndices.isEmpty()) {
            return results;
        }

        // 对每个出现位置进行局部分句作用域独立判定（杜绝全局整句短路导致多 occurrence 丢失或跨分句违规被降级）
        for (int occStart : occurrenceIndices) {
            int occEnd = occStart + marker.length();
            Assessment assessment = judgeSingleOccurrence(cleanSentence, marker, occStart, occEnd);
            results.add(assessment);
        }

        return results;
    }

    /**
     * 对单次出现的 marker 进行局部子句作用域判定。
     */
    private static Assessment judgeSingleOccurrence(
            String sentence, String marker, int occStart, int occEnd) {

        // 向左寻找局部子句边界（遇到子句标点或转折连词）
        int leftBound = 0;
        for (int i = occStart - 1; i >= 0; i--) {
            char c = sentence.charAt(i);
            if (c == '，' || c == ',' || c == '、' || c == ':' || c == '：' || c == ';' || c == '；') {
                leftBound = i + 1;
                break;
            }
        }

        // 检查在 [leftBound, occStart) 区间内是否存在转折连词
        String prefixZone = sentence.substring(leftBound, occStart);
        Matcher advMatcher = ADVERSATIVE_CONJUNCTION.matcher(prefixZone);
        int lastAdvIndex = -1;
        while (advMatcher.find()) {
            lastAdvIndex = leftBound + advMatcher.start();
        }
        if (lastAdvIndex != -1) {
            // 转折词划分了新的独立分句，左边界移至转折词起点
            leftBound = lastAdvIndex;
        }

        // 向右寻找局部子句边界（遇到标点或转折连词）
        int rightBound = sentence.length();
        for (int i = occEnd; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            if (c == '，' || c == ',' || c == '、' || c == ':' || c == '：' || c == ';' || c == '；'
                    || c == '。' || c == '！' || c == '？') {
                rightBound = i;
                break;
            }
        }

        // 检查在 [occEnd, rightBound) 区间内是否存在转折连词
        String suffixZone = sentence.substring(occEnd, rightBound);
        Matcher advRightMatcher = ADVERSATIVE_CONJUNCTION.matcher(suffixZone);
        if (advRightMatcher.find()) {
            rightBound = occEnd + advRightMatcher.start();
        }

        // 提取局部子句与其前缀/后缀
        String localClause = sentence.substring(leftBound, rightBound).strip();
        int localMarkerIdx = occStart - leftBound;
        String localPrefix = localMarkerIdx > 0 ? localClause.substring(0, localMarkerIdx).strip() : "";
        int localSuffixStart = localMarkerIdx + marker.length();
        String localSuffix = localSuffixStart < localClause.length()
                ? localClause.substring(localSuffixStart).strip() : "";

        // 检查前置独立分句（用于分析无转折时的宾语从句修饰）
        String priorText = sentence.substring(0, leftBound).strip();

        // 检查是否由转折词引导
        boolean isAdversative = ADVERSATIVE_CONJUNCTION.matcher(localPrefix).find()
                || (leftBound > 0 && ADVERSATIVE_CONJUNCTION.matcher(sentence.substring(Math.max(0, leftBound - 4), leftBound)).find());

        // A0. 局部双重否定检测：绑定当前局部子句，杜绝跨分句全局短路
        if (DOUBLE_NEGATION.matcher(localClause).find()) {
            return new Assessment(
                    marker,
                    AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW,
                    localClause,
                    "局部子句检测到双重否定结构，语义可能翻转，机械规则无法可靠保证，转人工复核",
                    RULE_VERSION
            );
        }

        // A. 复杂转折反差/反问模式检测（局部化修饰：杜绝无关事项的前置“理论上...实际上...”污染独立分句违规）
        // 模式 A1：反问句式直接修饰当前命题所在子句（如“难道可以...吗”）
        boolean rhetoricalInClause = localClause.contains("难道")
                || localClause.contains("岂能")
                || (localClause.contains("如果") && localClause.contains("吗"));
        if (rhetoricalInClause) {
            return new Assessment(
                    marker,
                    AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW,
                    localClause,
                    "局部子句包含反问或疑问假设语气，机械规则无法可靠保证肯定真实性，转人工复核",
                    RULE_VERSION
            );
        }

        // 模式 A2：前置认知模态 + 紧随转折反差修饰当前动作（如 Case F2: “并未明确……但实际上可以……”）
        if (isAdversative && EPISTEMIC_UNKNOWN.matcher(priorText).find()) {
            boolean transitionContrast = localClause.contains("实际上") || localClause.contains("其实")
                    || localClause.contains("事实是") || localClause.contains("实质上");
            if (transitionContrast) {
                return new Assessment(
                        marker,
                        AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW,
                        localClause,
                        "检测到‘未明确/否定……但实际上/假设’复杂反差句型，语义存在争议或口径冲突，转人工复核",
                        RULE_VERSION
                );
            }
        }

        // B. 前置动词直接否定（如 AQ06: "通常不可以先组织培训再补签合同"）
        if (marker.startsWith("可以") || marker.startsWith("允许")) {
            if (DIRECT_NEGATION_PREFIX.matcher(localPrefix).find()) {
                return new Assessment(
                        marker,
                        AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                        localClause,
                        "动词短语前存在直接否定修饰（如'不可以'、'不得'、'通常不'），构成命题否定",
                        RULE_VERSION
                );
            }
        }

        // C. 局部前缀中紧邻否定词（距离 marker 小于等于 8 个字符）
        if (!localPrefix.isEmpty()) {
            String immediatePrefix = localPrefix.length() <= 8 ? localPrefix : localPrefix.substring(localPrefix.length() - 8);
            if (DIRECT_NEGATION_PREFIX.matcher(immediatePrefix).find()) {
                return new Assessment(
                        marker,
                        AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                        localClause,
                        "命题紧邻前置否定词修饰，属于否定陈述",
                        RULE_VERSION
                );
            }
        }

        // C2. 局部后缀紧邻后置否定谓语（如 marker + "是不允许的" / "是禁止的" / "是不合规的" / "是不适用的"）
        if (!localSuffix.isEmpty()) {
            Matcher suffixNegMatcher = SUFFIX_NEGATION.matcher(localSuffix);
            if (suffixNegMatcher.find()) {
                return new Assessment(
                        marker,
                        AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                        localClause,
                        "命题紧邻后置否定谓语修饰（如'是不允许的'、'是禁止的'、'是不合规的'），构成命题否定",
                        RULE_VERSION
                );
            }
        }

        // D. 例外条款分析（如特批例外，绑定当前局部子句与紧邻从句作用域，杜绝全句污染）
        boolean inLocalException = EXCEPTION_MARKER.matcher(localClause).find();
        boolean inExceptionScope = inLocalException || isPrecededByExceptionClause(sentence, leftBound, isAdversative);
        if (inExceptionScope) {
            boolean hasLocalNegation = localClause.contains("不得")
                    || localClause.contains("不能")
                    || localClause.contains("不可以")
                    || localClause.contains("禁止")
                    || DIRECT_NEGATION_PREFIX.matcher(localPrefix).find();

            if (hasLocalNegation) {
                return new Assessment(
                        marker,
                        AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                        localClause,
                        "局部子句陈述制度例外条款与禁止性主干（如'除非...不得/不可以...'），非肯定违规",
                        RULE_VERSION
                );
            } else {
                return new Assessment(
                        marker,
                        AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW,
                        localClause,
                        "局部子句包含例外条件许可或特殊结构（如'除非...才可以...'），需人工复核是否合规",
                        RULE_VERSION
                );
            }
        }

        // E. 认识模态与未知状态表达（收紧到实际修饰作用域）
        // 模式 E1：同分句直接修饰（localPrefix 包含认识模态短语，且带未知指示词或紧邻 marker，无主语代词介入）
        if (EPISTEMIC_UNKNOWN.matcher(localPrefix).find()) {
            boolean hasSubjectOrAgent = SUBJECT_OR_AGENT.matcher(localPrefix).find();
            if (!hasSubjectOrAgent) {
                boolean hasUnknownIndicator = UNKNOWN_INDICATOR.matcher(localPrefix).find()
                        || UNKNOWN_INDICATOR.matcher(localSuffix).find();
                boolean directlyModifiesClause = localPrefix.endsWith("明确")
                        || localPrefix.endsWith("确定")
                        || localPrefix.endsWith("提及")
                        || localPrefix.endsWith("载明")
                        || localPrefix.endsWith("说明")
                        || localPrefix.endsWith("确认")
                        || localPrefix.endsWith("规定")
                        || localPrefix.endsWith("是否")
                        || localPrefix.endsWith("关于");

                if (hasUnknownIndicator || directlyModifiesClause) {
                    return new Assessment(
                            marker,
                            AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                            localClause,
                            "命题处于明确的资料未明确/尚未确定等认识模态修饰语境中，非肯定断言",
                            RULE_VERSION
                    );
                }
            }
        }

        // 模式 E2：跨逗号引语/从句修饰（无转折词阻断，且带有疑问/未知指示词）
        if (!isAdversative && EPISTEMIC_UNKNOWN.matcher(priorText).find()) {
            // 前置分句有认识模态，且当前分句含有未知指示词（如“具体每年抽查多少份”），且无肯定强制词
            if (UNKNOWN_INDICATOR.matcher(localClause).find() && !localClause.contains("要求") && !localClause.contains("必须")) {
                return new Assessment(
                        marker,
                        AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION,
                        localClause,
                        "命题处于前置认识模态统领的疑问/未知补足分句中，非肯定断言",
                        RULE_VERSION
                );
            }
        }

        // F. 经过以上各层级排除，分句独立做出肯定陈述 -> 判定为肯定违规
        String reason = isAdversative
                ? "转折分句独立肯定断言禁断命题，不受其他分句认识模态影响"
                : "机械证据确凿：分句在无否定、无未知模态修饰的情况下肯定断言了禁断命题";

        return new Assessment(
                marker,
                AssertionVerdict.AFFIRMATIVE_VIOLATION,
                localClause,
                reason,
                RULE_VERSION
        );
    }

    /**
     * 判断当前子句是否处于前置例外条件从句的直接修饰范围下。
     * <p>必须满足：
     * 1. 当前子句非转折连词引导（转折连词切断前置修饰）；
     * 2. 紧邻前置分句（或经短状语修饰的前置分句）包含例外条款标记；
     * 3. 避免跨越已闭合的完整主干从句产生长程污染。</p>
     */
    private static boolean isPrecededByExceptionClause(String sentence, int leftBound, boolean isAdversative) {
        if (isAdversative || leftBound <= 0) {
            return false;
        }
        String priorText = sentence.substring(0, leftBound).strip();
        if (priorText.endsWith("，") || priorText.endsWith(",")) {
            priorText = priorText.substring(0, priorText.length() - 1).strip();
        }
        if (priorText.isEmpty()) {
            return false;
        }

        String[] priorClauses = priorText.split("[，,；;：:]");
        if (priorClauses.length == 0) {
            return false;
        }

        String lastClause = priorClauses[priorClauses.length - 1].strip();

        // 如果紧邻前置从句自身已经形成闭合的禁止/动作结构（如“除非...否则不得推迟上线”），不得向后传递到无关命题
        if (isSelfContainedExceptionClause(lastClause)) {
            return false;
        }

        if (EXCEPTION_MARKER.matcher(lastClause).find()) {
            return true;
        }

        if (lastClause.length() <= 6 && priorClauses.length >= 2) {
            String secondLastClause = priorClauses[priorClauses.length - 2].strip();
            if (isSelfContainedExceptionClause(secondLastClause)) {
                return false;
            }
            if (EXCEPTION_MARKER.matcher(secondLastClause).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断前置例外从句是否已经在其自身分句内形成了完整闭合的禁止/动作结构（如“除非...否则不得推迟上线”）。
     */
    private static boolean isSelfContainedExceptionClause(String clause) {
        if (!EXCEPTION_MARKER.matcher(clause).find()) {
            return false;
        }
        // 同时包含条件引导与内部禁止谓语（例如“否则不得”、“不得”、“禁止”、“不能”后接动作）
        return clause.contains("否则不得")
                || clause.contains("否则不能")
                || clause.contains("否则禁止")
                || clause.contains("否则不可以")
                || Pattern.compile("(?:不得|禁止|严禁|不能|不可以)\\S{2,}").matcher(clause).find();
    }
}
