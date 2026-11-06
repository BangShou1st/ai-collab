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

    private List<String> extractTerms(String query) {
        String[] tokens = SPLIT_PATTERN.split(query.toLowerCase(Locale.ROOT));
        Set<String> terms = new LinkedHashSet<>();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.length() >= 2 && !STOP_WORDS.contains(trimmed)) {
                terms.add(trimmed);
            }
        }

        // 若分词过滤后为空，对纯中文或简短查询补充单字/字符片段
        if (terms.isEmpty()) {
            for (String token : tokens) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty() && !STOP_WORDS.contains(trimmed)) {
                    terms.add(trimmed);
                }
            }
        }

        // 中文连续短语补充 2-gram
        String cleanChinese = query.replaceAll("[^\\u4e00-\\u9fa5]", "");
        if (cleanChinese.length() >= 2 && cleanChinese.length() <= 10) {
            for (int i = 0; i < cleanChinese.length() - 1; i++) {
                String bi = cleanChinese.substring(i, i + 2);
                if (!STOP_WORDS.contains(bi)) {
                    terms.add(bi);
                }
            }
        }

        return new ArrayList<>(terms);
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
