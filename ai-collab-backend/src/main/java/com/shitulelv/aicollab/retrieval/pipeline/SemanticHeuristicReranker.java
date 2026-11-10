package com.shitulelv.aicollab.retrieval.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 语义启发式重排器。
 *
 * <p>结合 heading 匹配、核心词覆盖率和 RRF 基础得分进行加权重排。
 * <p>核心契约：
 * <ul>
 *   <li>严禁修改、伪造或删除原始证据内容及来源身份（chunkId/documentId/metadata/heading/content/contentHash）。</li>
 *   <li>遇到异常或计算超时时，安全降级返回原候选列表，并标记 rerankerDegraded。</li>
 * </ul>
 */
@Component
public class SemanticHeuristicReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(SemanticHeuristicReranker.class);
    public static final String NAME = "semantic-heuristic";
    private static final double EPSILON = 1e-9;
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[\\p{P}\\p{S}\\s]+");

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "自己", "这", "如何", "怎么", "为什么", "什么", "哪些", "及", "与", "或",
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of",
            "with", "is", "are", "was", "were", "it", "this", "that", "how", "what", "why"
    );

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        try {
            return doRerank(query, candidates, topN);
        } catch (Throwable t) {
            log.warn("SemanticHeuristicReranker 执行异常，安全降级至原 RRF 候选: {}", t.getMessage(), t);
            return degrade(candidates, topN);
        }
    }

    private List<RetrievalCandidate> doRerank(String query, List<RetrievalCandidate> candidates, int topN) {
        if (query == null || query.isBlank()) {
            return degrade(candidates, topN);
        }

        List<String> terms = extractTerms(query);
        if (terms.isEmpty()) {
            return degrade(candidates, topN);
        }

        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        List<RetrievalCandidate> scored = new ArrayList<>(candidates.size());

        for (RetrievalCandidate candidate : candidates) {
            double headingScore = calculateHeadingScore(candidate.heading(), normalizedQuery, terms);
            double contentScore = calculateContentScore(candidate.content(), terms);
            double rrf = candidate.rrfScore() != null ? candidate.rrfScore() : 0.0;

            // 权重分配：0.35 * RRF 得分 + 0.40 * 内容核心词覆盖 + 0.25 * 标题匹配
            double combinedScore = (rrf * 0.35) + (contentScore * 0.40) + (headingScore * 0.25);

            // 严格遵守证据不可变契约：保留原始 chunkId、documentId、heading、content、metadata
            scored.add(candidate.withRerank(combinedScore, name()));
        }

        // 稳定排序比较器：rerankScore 降序 -> rrfScore 降序 -> chunkId 升序
        scored.sort((a, b) -> {
            int scoreCmp = Double.compare(b.rerankScore(), a.rerankScore());
            if (scoreCmp != 0) {
                return scoreCmp;
            }

            double rrfA = a.rrfScore() != null ? a.rrfScore() : 0.0;
            double rrfB = b.rrfScore() != null ? b.rrfScore() : 0.0;
            if (Math.abs(rrfA - rrfB) > EPSILON) {
                return Double.compare(rrfB, rrfA);
            }

            return a.id().compareTo(b.id());
        });

        if (topN > 0 && scored.size() > topN) {
            return new ArrayList<>(scored.subList(0, topN));
        }
        return scored;
    }

    private double calculateHeadingScore(String heading, String normalizedQuery, List<String> terms) {
        if (heading == null || heading.isBlank()) {
            return 0.0;
        }

        String normalizedHeading = heading.trim().toLowerCase(Locale.ROOT);
        if (normalizedHeading.contains(normalizedQuery)) {
            return 1.0;
        }

        int matched = 0;
        for (String term : terms) {
            if (normalizedHeading.contains(term)) {
                matched++;
            }
        }
        return (double) matched / terms.size();
    }

    private double calculateContentScore(String content, List<String> terms) {
        if (content == null || content.isBlank()) {
            return 0.0;
        }

        String lowerContent = content.toLowerCase(Locale.ROOT);
        int matched = 0;
        int occurrences = 0;

        for (String term : terms) {
            int index = lowerContent.indexOf(term);
            if (index >= 0) {
                matched++;
                while (index >= 0 && occurrences < 10) {
                    occurrences++;
                    index = lowerContent.indexOf(term, index + term.length());
                }
            }
        }

        double coverage = (double) matched / terms.size();
        double frequencyBonus = Math.min(0.15, occurrences * 0.02);
        return Math.min(1.0, coverage + frequencyBonus);
    }

    /** 词项总数上限：有界处理，防止极长查询产生过量词项拖垮匹配开销。 */
    private static final int MAX_TERMS = 64;
    /** 中文 bigram 采样上限：超过后按均匀步长采样，保证长句全程覆盖而非只取前缀。 */
    private static final int MAX_CHINESE_BIGRAMS = 48;
    /**
     * 单个中文 token 直接作为词项的最大长度：超过后整串匹配窗口过宽、几乎必然无命中
     * （历史缺陷：无标点中文整句成为单一超长 term，contentScore/headingScore≈0），
     * 改为依赖 bigram 表达。短中文短语（如“住宿费标准”）仍保留整词项以获得精确匹配。
     */
    private static final int CJK_TOKEN_MAX_LENGTH = 10;
    /** 常见虚词单字：出现在 bigram 两端时该 bigram 几乎不携带可匹配语义，过滤以降噪。 */
    private static final Set<Character> CJK_FUNCTION_CHARS = Set.of(
            '的', '了', '在', '是', '和', '与', '或', '及', '对', '为', '等', '把', '被', '向', '从', '到', '要', '会', '有', '都');

    private List<String> extractTerms(String query) {
        String lowercased = query.toLowerCase(Locale.ROOT);
        String[] tokens = SPLIT_PATTERN.split(lowercased);
        Set<String> terms = new LinkedHashSet<>();

        // 1. 词级切分：英文/数字/类名/API 片段等非 CJK token 原样保留；短中文 token 保留整词项，
        //    超长中文 token（无标点整句）不再整串入库，交由第 2 步的 bigram 表达。
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty() || STOP_WORDS.contains(trimmed)) {
                continue;
            }
            if (containsCjk(trimmed)) {
                if (trimmed.codePointCount(0, trimmed.length()) <= CJK_TOKEN_MAX_LENGTH) {
                    terms.add(trimmed);
                }
            } else if (trimmed.length() >= 2) {
                terms.add(trimmed);
            }
        }

        // 2. 中文 bigram：对每个含中文 token 的连续汉字段生成 2-gram，长度不再限制在 10 以内；
        //    超出采样上限时按均匀步长采样，覆盖整个长句。每个 bigram 都过滤虚词端点噪声。
        for (String token : tokens) {
            if (!containsCjk(token)) {
                continue;
            }
            String cleanChinese = token.replaceAll("[^\\u4e00-\\u9fa5]", "");
            addChineseBigrams(cleanChinese, terms);
        }

        // 3. 兜底：过滤后为空时退回原始 token（含单字），保证重排器总能产出稳定、可复现的排序。
        if (terms.isEmpty()) {
            for (String token : tokens) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty() && !STOP_WORDS.contains(trimmed)) {
                    terms.add(trimmed);
                }
            }
        }

        // 4. 有界截断：保持插入顺序（确定性），优先保留先出现的词项。
        if (terms.size() > MAX_TERMS) {
            List<String> bounded = new ArrayList<>(MAX_TERMS);
            for (String term : terms) {
                if (bounded.size() == MAX_TERMS) break;
                bounded.add(term);
            }
            return bounded;
        }
        return new ArrayList<>(terms);
    }

    private void addChineseBigrams(String cleanChinese, Set<String> terms) {
        int length = cleanChinese.length();
        if (length < 2) {
            return;
        }
        int bigramCount = length - 1;
        int step = bigramCount <= MAX_CHINESE_BIGRAMS ? 1 : (bigramCount + MAX_CHINESE_BIGRAMS - 1) / MAX_CHINESE_BIGRAMS;
        for (int i = 0; i < bigramCount; i += step) {
            String bigram = cleanChinese.substring(i, i + 2);
            if (STOP_WORDS.contains(bigram)
                    || CJK_FUNCTION_CHARS.contains(bigram.charAt(0))
                    || CJK_FUNCTION_CHARS.contains(bigram.charAt(1))) {
                continue;
            }
            terms.add(bigram);
        }
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fa5') {
                return true;
            }
        }
        return false;
    }

    private List<RetrievalCandidate> degrade(List<RetrievalCandidate> candidates, int topN) {
        List<RetrievalCandidate> target = candidates;
        if (topN > 0 && candidates.size() > topN) {
            target = candidates.subList(0, topN);
        }

        List<RetrievalCandidate> degraded = new ArrayList<>(target.size());
        for (RetrievalCandidate candidate : target) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("rerankerDegraded", true);
            degraded.add(candidate.withMetadata(extra).withRerank(
                    candidate.rrfScore() != null ? candidate.rrfScore() : 0.0,
                    "degraded"
            ));
        }
        return degraded;
    }
}
