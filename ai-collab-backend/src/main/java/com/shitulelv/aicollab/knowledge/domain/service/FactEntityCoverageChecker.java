package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 轻量级事实实体覆盖率检测器 (FactEntityCoverageChecker)。
 *
 * <p>提取回答句子中的关键实体、数值、配置指标或强断言，核验对应引用片段中是否具备事实依据。
 * <p>纯内存计算，无外部 RPC，零额外 Token 消耗。若存在明显脱离引用的强断言或虚构数值，记录 unsupportedClaims 警告。
 */
@Component
public class FactEntityCoverageChecker {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[^。！？\\n;；]+[。！？\\n;；]?");
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S\\d+(?:\\s*,\\s*S\\d+)*)\\]");
    private static final Pattern NUMBER_ENTITY_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?%?\\b");
    private static final Pattern CODE_IDENTIFIER_PATTERN = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_.-]{2,}\\b");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("[“\"'《]([^“”\"'》]{2,30})[”\"'》]");

    private static final Pattern STRONG_CLAIM_PATTERN = Pattern.compile(
            ".*(?:必须|严禁|绝对|永远|禁止|确保|100%|所有|全部|唯一|总计|完全|承诺|指标为|端口为|密码为|密钥为).*"
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "自己", "这", "如何", "怎么", "为什么", "什么", "哪些", "及", "与", "或", "根据",
            "文档", "资料", "项目", "说明", "显示", "提到", "指出", "参见", "可以", "能够"
    );

    public List<String> check(String answer, List<KnowledgeSource> citedSources) {
        if (answer == null || answer.isBlank() || citedSources == null || citedSources.isEmpty()) {
            return List.of();
        }

        // 收集各来源与全局语料库（转为小写规范化）
        Map<Integer, String> sourceCorpusByRank = new HashMap<>();
        StringBuilder globalCorpusBuilder = new StringBuilder();
        for (KnowledgeSource src : citedSources) {
            String text = (src.heading() != null ? src.heading() + " " : "") + (src.content() != null ? src.content() : "");
            String lower = text.toLowerCase(Locale.ROOT);
            sourceCorpusByRank.put(src.rank(), lower);
            globalCorpusBuilder.append(lower).append(" ");
        }
        String globalCorpus = globalCorpusBuilder.toString();

        List<String> warnings = new ArrayList<>();
        Matcher sentenceMatcher = SENTENCE_SPLIT.matcher(answer);

        while (sentenceMatcher.find()) {
            String sentence = sentenceMatcher.group().trim();
            if (sentence.length() < 4) continue;

            // 提取本句绑定的引用序号
            Set<Integer> sentenceRanks = extractCitationRanks(sentence);
            String evidenceScope;
            if (!sentenceRanks.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Integer r : sentenceRanks) {
                    String sText = sourceCorpusByRank.get(r);
                    if (sText != null) sb.append(sText).append(" ");
                }
                evidenceScope = sb.toString();
            } else {
                evidenceScope = globalCorpus;
            }

            // 清理句子中的引用标记后进行实体检查
            String cleanSentence = sentence.replaceAll("\\[S\\d+(?:\\s*,\\s*S\\d+)*\\]", "").trim();
            if (cleanSentence.isBlank()) continue;

            // 1. 数值与比率实体检测（数字若在引用中完全不存在，极可能是模型幻觉编造）
            Matcher numMatcher = NUMBER_ENTITY_PATTERN.matcher(cleanSentence);
            while (numMatcher.find()) {
                String num = numMatcher.group();
                if (num.length() >= 2 && !evidenceScope.contains(num.toLowerCase(Locale.ROOT))) {
                    warnings.add(String.format("句子 '%s' 中包含的关键数值/指标 '%s' 在所引用的事实资料中未找到依据",
                            truncate(cleanSentence, 40), num));
                }
            }

            // 2. 引号内特定实体或专有名称核验
            Matcher quoteMatcher = QUOTED_PATTERN.matcher(cleanSentence);
            while (quoteMatcher.find()) {
                String term = quoteMatcher.group(1).trim().toLowerCase(Locale.ROOT);
                if (term.length() >= 3 && !evidenceScope.contains(term)) {
                    warnings.add(String.format("句子 '%s' 中断言的专有名词 '%s' 在所引用的事实资料中未找到依据",
                            truncate(cleanSentence, 40), term));
                }
            }

            // 3. 强断言语句核验：若包含“必须/严禁/绝对/100%/唯一”等强断言，但核心词未在证据中命中
            if (STRONG_CLAIM_PATTERN.matcher(cleanSentence).matches()) {
                List<String> terms = extractSignificantTerms(cleanSentence);
                long matchedCount = terms.stream().filter(evidenceScope::contains).count();
                if (!terms.isEmpty() && (double) matchedCount / terms.size() < 0.25) {
                    warnings.add(String.format("句子 '%s' 作出了高确信度强断言，但与引用资料事实匹配度过低 (覆盖率 %.0f%%)",
                            truncate(cleanSentence, 40), ((double) matchedCount / terms.size()) * 100));
                }
            }
        }

        return warnings;
    }

    private Set<Integer> extractCitationRanks(String sentence) {
        Set<Integer> ranks = new HashSet<>();
        Matcher m = CITATION_PATTERN.matcher(sentence);
        while (m.find()) {
            String group = m.group(1);
            String[] parts = group.split(",");
            for (String part : parts) {
                String s = part.trim();
                if (s.startsWith("S")) {
                    try {
                        ranks.add(Integer.parseInt(s.substring(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return ranks;
    }

    private List<String> extractSignificantTerms(String sentence) {
        List<String> terms = new ArrayList<>();
        // 提取 2 字符以上中文词
        String clean = sentence.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", " ");
        String[] tokens = clean.split("\\s+");
        for (String t : tokens) {
            String lower = t.trim().toLowerCase(Locale.ROOT);
            if (lower.length() >= 2 && !STOP_WORDS.contains(lower)) {
                terms.add(lower);
            }
        }
        return terms;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
