package com.shitulelv.aicollab.retrieval.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decomposes complex, multi-intent, or comparative queries into 2 to 4 complementary subqueries.
 *
 * Strict bounds:
 * - Upper bound: maximum 4 subqueries (prevents unbounded expansion).
 * - Lower bound: if fewer than 2 valid subqueries are produced, safely falls back to the original query.
 * - Context inheritance: each subquery inherits original document constraints and metadata filters.
 * - Fault tolerance: any decomposition error safely falls back to the single original query.
 */
public class MultiQueryDecomposer {
    private static final Logger log = LoggerFactory.getLogger(MultiQueryDecomposer.class);

    private static final int MAX_SUB_QUERIES = 4;
    private static final int MIN_SUB_QUERIES = 2;

    // Pattern for splitting multiple distinct questions or numbered items
    private static final Pattern QUESTION_SPLIT_PATTERN = Pattern.compile(
            "[?？;；\\n]+|(?:^|\\s+)(?:[1-4][\\.、]|(?:第[一二三四][\\.、]?))\\s*"
    );

    // Pattern for comparative questions between two entities
    private static final Pattern COMPARISON_PATTERN = Pattern.compile(
            "^(.+?)\\s*(?:和|与|跟|及|以及|vs|versus)\\s*(.+?)\\s*(?:的)?\\s*(?:区别|不同点|异同|对比|优缺点|优劣|相比)(?:是什么|有哪些|分析)?\\??$",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for coordinating conjunctions connecting distinct demands
    private static final Pattern CONJUNCTION_SPLIT_PATTERN = Pattern.compile(
            "[,，]?\\s*(?:并且|而且|同时|以及|另外)\\s*"
    );

    /**
     * Decomposes a complex query string into 2~4 subqueries.
     *
     * @param complexQuery the composite user query
     * @return bounded list of complementary subqueries, or single-element list on fallback
     */
    public List<String> decompose(String complexQuery) {
        if (complexQuery == null || complexQuery.isBlank()) {
            return List.of();
        }
        String cleanQuery = complexQuery.strip();

        try {
            List<String> rawSubQueries = doDecompose(cleanQuery);

            // Deduplicate and filter out fragments
            List<String> validSubQueries = new ArrayList<>();
            for (String sub : rawSubQueries) {
                String normalized = sub.replaceAll("^[，,。；;：:\\s]+", "")
                                       .replaceAll("[，,。；;：:\\s]+$", "")
                                       .trim();
                if (normalized.length() >= 2 && !validSubQueries.contains(normalized)) {
                    validSubQueries.add(normalized);
                }
            }

            if (validSubQueries.size() < MIN_SUB_QUERIES) {
                log.debug("Decomposition resulted in {} subqueries, falling back to original query", validSubQueries.size());
                return List.of(cleanQuery);
            }

            // Strictly bounded to maximum 4
            if (validSubQueries.size() > MAX_SUB_QUERIES) {
                log.debug("Truncating decomposed subqueries from {} to {}", validSubQueries.size(), MAX_SUB_QUERIES);
                validSubQueries = validSubQueries.subList(0, MAX_SUB_QUERIES);
            }

            return List.copyOf(validSubQueries);

        } catch (Exception e) {
            log.warn("MultiQuery decomposition encountered an exception, safely falling back to original: {}", e.getMessage());
            return List.of(cleanQuery);
        }
    }

    /**
     * Decomposes a StructuredQuery, where each resulting subquery inherits
     * the exact original metadata filters and document scope constraints.
     *
     * @param structuredQuery composite query with filters
     * @return bounded list of StructuredQuery items
     */
    public List<StructuredQuery> decomposeStructured(StructuredQuery structuredQuery) {
        if (structuredQuery == null) {
            return List.of();
        }
        String query = structuredQuery.semanticQuery();
        List<String> subQueryTexts = decompose(query);
        if (subQueryTexts.isEmpty()) {
            return List.of(structuredQuery);
        }

        List<StructuredQuery> result = new ArrayList<>();
        for (String subText : subQueryTexts) {
            // Each subquery strictly inherits the parent's filters
            result.add(new StructuredQuery(subText, structuredQuery.filters()));
        }
        return List.copyOf(result);
    }

    private List<String> doDecompose(String query) {
        // Strategy 1: Comparative query (Entity A vs Entity B)
        Matcher compMatcher = COMPARISON_PATTERN.matcher(query);
        if (compMatcher.matches()) {
            String entityA = compMatcher.group(1).trim();
            String entityB = compMatcher.group(2).trim();
            if (!entityA.isEmpty() && !entityB.isEmpty() && entityA.length() <= 30 && entityB.length() <= 30) {
                return List.of(
                        entityA + " 的定义与特性",
                        entityB + " 的定义与特性",
                        entityA + " 与 " + entityB + " 的区别与对比"
                );
            }
        }

        // Strategy 2: Splitting by multiple question marks or numbered list items
        String[] qParts = QUESTION_SPLIT_PATTERN.split(query);
        List<String> candidates = new ArrayList<>();
        for (String part : qParts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                candidates.add(trimmed);
            }
        }

        if (candidates.size() >= 2) {
            return propagateSubjectIfElliptical(candidates);
        }

        // Strategy 3: Splitting by coordinating conjunctions ("并且", "同时", "而且")
        String[] conjParts = CONJUNCTION_SPLIT_PATTERN.split(query);
        if (conjParts.length >= 2) {
            List<String> conjCandidates = new ArrayList<>();
            for (String part : conjParts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    conjCandidates.add(trimmed);
                }
            }
            if (conjCandidates.size() >= 2) {
                return propagateSubjectIfElliptical(conjCandidates);
            }
        }

        return List.of(query);
    }

    /**
     * If subsequent clauses are elliptical (e.g. "责任人是谁？" lacking subject),
     * inherits the leading subject from the first clause to make the subquery standalone.
     */
    private List<String> propagateSubjectIfElliptical(List<String> clauses) {
        if (clauses.isEmpty()) return clauses;

        List<String> enriched = new ArrayList<>();
        String firstClause = clauses.get(0);
        enriched.add(firstClause);

        String topic = extractLeadingTopic(firstClause);

        for (int i = 1; i < clauses.size(); i++) {
            String current = clauses.get(i);
            if (isEllipticalClause(current) && topic != null && !topic.isBlank()) {
                enriched.add(topic + current);
            } else {
                enriched.add(current);
            }
        }
        return enriched;
    }

    private boolean isEllipticalClause(String text) {
        if (text.length() <= 10) return true;
        return text.startsWith("谁是") ||
               text.startsWith("责任人") ||
               text.startsWith("有哪些") ||
               text.startsWith("如何") ||
               text.startsWith("能否") ||
               text.startsWith("怎么处理") ||
               text.startsWith("有什么") ||
               text.startsWith("具体");
    }

    private String extractLeadingTopic(String clause) {
        if (clause == null || clause.length() < 3) return null;
        // Common prefixes before temporal or question markers
        int idx = -1;
        for (String marker : List.of("前", "后", "中", "时", "有哪些", "怎么", "如何", "是什么")) {
            int pos = clause.indexOf(marker);
            if (pos > 1 && (idx == -1 || pos < idx)) {
                idx = pos;
            }
        }
        if (idx > 2 && idx <= 15) {
            return clause.substring(0, idx).trim();
        }
        return null;
    }
}
