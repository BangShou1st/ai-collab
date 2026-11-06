package com.shitulelv.aicollab.retrieval.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 句子窗口检索扩展器 (Sentence Window Retrieval)。
 *
 * <p>核心能力：
 * <ul>
 *   <li>识别句子边界（支持中文标点 。！？； 及英文标点，严格保护引号与括号完整性）。</li>
 *   <li>支持配置窗口大小（前后各 1~3 句，默认 1 句）。</li>
 *   <li>命中候选后，从所属文档同一版本/同一章节内容中恢复前后句完整窗口。</li>
 *   <li>窗口重叠时自动合并去重，严禁跨章节、跨文档、跨版本边界，且绝不损坏原始引用坐标。</li>
 *   <li>如果是表格或代码块，则不作为句子窗口展开。</li>
 * </ul>
 */
@Component
public class SentenceWindowExpander {

    public static final int DEFAULT_WINDOW_SIZE = 1;
    public static final int MIN_WINDOW_SIZE = 1;
    public static final int MAX_WINDOW_SIZE = 3;

    private final DocumentSectionReader sectionReader;

    public SentenceWindowExpander() {
        this(null);
    }

    @Autowired
    public SentenceWindowExpander(@Autowired(required = false) DocumentSectionReader sectionReader) {
        this.sectionReader = sectionReader;
    }

    public List<RetrievalCandidate> expand(List<RetrievalCandidate> candidates) {
        return expand(null, candidates, DEFAULT_WINDOW_SIZE);
    }

    public List<RetrievalCandidate> expand(List<RetrievalCandidate> candidates, int windowSize) {
        return expand(null, candidates, windowSize);
    }

    public List<RetrievalCandidate> expand(UUID projectId, List<RetrievalCandidate> candidates, int windowSize) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        int boundedWindow = Math.max(MIN_WINDOW_SIZE, Math.min(MAX_WINDOW_SIZE, windowSize));

        // 按 (documentId, bodySnapshotId, heading) 分区，确保严禁跨文档、跨版本、跨章节合并
        Map<PartitionKey, List<CandidateWithSpan>> partitions = new LinkedHashMap<>();
        List<RetrievalCandidate> skippedCandidates = new ArrayList<>();

        for (RetrievalCandidate candidate : candidates) {
            // 表格或代码块直接保留原样，不展开句子窗口
            if (isTableOrCodeBlock(candidate)) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("sentenceWindowSkipped", "TABLE_OR_CODE_BLOCK");
                skippedCandidates.add(candidate.withMetadata(extra));
                continue;
            }

            PartitionKey key = PartitionKey.of(candidate);
            String sectionText = resolveSectionText(projectId, candidate);
            List<SentenceSpan> sentences = splitSentences(sectionText);

            if (sentences.isEmpty()) {
                skippedCandidates.add(candidate);
                continue;
            }

            int[] matchedRange = findCandidateSentenceRange(candidate, sentences);
            int startSentence = matchedRange[0];
            int endSentence = matchedRange[1];

            int windowStart = Math.max(0, startSentence - boundedWindow);
            int windowEnd = Math.min(sentences.size() - 1, endSentence + boundedWindow);

            CandidateWithSpan withSpan = new CandidateWithSpan(
                    candidate,
                    sentences,
                    windowStart,
                    windowEnd,
                    startSentence,
                    endSentence
            );
            partitions.computeIfAbsent(key, k -> new ArrayList<>()).add(withSpan);
        }

        List<RetrievalCandidate> expandedResult = new ArrayList<>();

        // 对每个独立分区处理窗口合并与去重
        for (List<CandidateWithSpan> group : partitions.values()) {
            // 按窗口起始句子索引升序排列
            group.sort(Comparator.comparingInt(c -> c.windowStart));

            int i = 0;
            while (i < group.size()) {
                CandidateWithSpan current = group.get(i);
                int mergedStart = current.windowStart;
                int mergedEnd = current.windowEnd;
                List<CandidateWithSpan> overlapping = new ArrayList<>();
                overlapping.add(current);

                // 寻找在同一章节内重叠的窗口
                int j = i + 1;
                while (j < group.size()) {
                    CandidateWithSpan next = group.get(j);
                    if (next.windowStart <= mergedEnd) {
                        // 窗口重叠，合并区间
                        mergedEnd = Math.max(mergedEnd, next.windowEnd);
                        overlapping.add(next);
                        j++;
                    } else {
                        break;
                    }
                }

                if (overlapping.size() == 1) {
                    // 无重叠，单一窗口展开
                    expandedResult.add(buildExpandedCandidate(current, mergedStart, mergedEnd, false, List.of(current.candidate.id())));
                } else {
                    // 有重叠，合并去重：选取 RRF/Rerank 得分最高的候选作为代表，聚合 chunk IDs
                    overlapping.sort((a, b) -> {
                        double scoreA = a.candidate.rerankScore() != null ? a.candidate.rerankScore() : a.candidate.rrfScore();
                        double scoreB = b.candidate.rerankScore() != null ? b.candidate.rerankScore() : b.candidate.rrfScore();
                        return Double.compare(scoreB, scoreA);
                    });

                    CandidateWithSpan primary = overlapping.getFirst();
                    List<UUID> mergedChunkIds = overlapping.stream().map(c -> c.candidate.id()).toList();

                    expandedResult.add(buildExpandedCandidate(primary, mergedStart, mergedEnd, true, mergedChunkIds));
                }

                i = j;
            }
        }

        // 合并表格/代码块等跳过项，保持原有优先级秩序
        expandedResult.addAll(skippedCandidates);

        // 重新按得分稳定排序
        expandedResult.sort((a, b) -> {
            double scoreA = a.rerankScore() != null ? a.rerankScore() : a.rrfScore();
            double scoreB = b.rerankScore() != null ? b.rerankScore() : b.rrfScore();
            int cmp = Double.compare(scoreB, scoreA);
            if (cmp != 0) return cmp;
            return a.id().compareTo(b.id());
        });

        return expandedResult;
    }

    private RetrievalCandidate buildExpandedCandidate(
            CandidateWithSpan target,
            int windowStart,
            int windowEnd,
            boolean isMerged,
            List<UUID> mergedChunkIds) {

        List<SentenceSpan> sentences = target.sentences;
        StringBuilder sb = new StringBuilder();
        for (int k = windowStart; k <= windowEnd; k++) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(sentences.get(k).text());
        }
        String expandedText = sb.toString();

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("expandedContent", expandedText);
        extra.put("sentenceWindowMerged", isMerged);
        extra.put("mergedChunkIds", mergedChunkIds);
        extra.put("windowSentenceCount", windowEnd - windowStart + 1);
        extra.put("windowSentenceStart", windowStart);
        extra.put("windowSentenceEnd", windowEnd);

        // 严格保护原始证据契约：chunkId, documentId, heading, content, contentHash 及原始 charFrom/charThrough 原样保留
        return target.candidate.withMetadata(extra);
    }

    private String resolveSectionText(UUID projectId, RetrievalCandidate candidate) {
        String snapshotId = candidate.metadata().get("bodySnapshotId") != null
                ? String.valueOf(candidate.metadata().get("bodySnapshotId"))
                : null;

        if (sectionReader != null) {
            Optional<String> secText = sectionReader.getSectionText(
                    projectId,
                    candidate.documentId(),
                    candidate.heading(),
                    snapshotId
            );
            if (secText.isPresent() && !secText.get().isBlank()) {
                return secText.get();
            }
        }

        Object parentSnippet = candidate.metadata().get("parentSnippet");
        if (parentSnippet instanceof String s && !s.isBlank()) {
            return s;
        }

        Object sectionContent = candidate.metadata().get("sectionContent");
        if (sectionContent instanceof String s && !s.isBlank()) {
            return s;
        }

        return candidate.content();
    }

    private int[] findCandidateSentenceRange(RetrievalCandidate candidate, List<SentenceSpan> sentences) {
        String chunkContent = candidate.content().trim();
        int first = -1;
        int last = -1;

        for (int i = 0; i < sentences.size(); i++) {
            SentenceSpan span = sentences.get(i);
            if (chunkContent.contains(span.text()) || span.text().contains(chunkContent)) {
                if (first == -1) first = i;
                last = i;
            }
        }

        if (first == -1) {
            // 未直接匹配到完整子句时，做词项重叠匹配
            int maxOverlap = 0;
            int bestIdx = 0;
            for (int i = 0; i < sentences.size(); i++) {
                int overlap = commonChars(chunkContent, sentences.get(i).text());
                if (overlap > maxOverlap) {
                    maxOverlap = overlap;
                    bestIdx = i;
                }
            }
            first = bestIdx;
            last = bestIdx;
        }

        return new int[]{first, last};
    }

    private int commonChars(String a, String b) {
        int count = 0;
        int minLen = Math.min(a.length(), b.length());
        for (int i = 0; i < minLen; i++) {
            if (a.charAt(i) == b.charAt(i)) count++;
        }
        return count;
    }

    public static boolean isTableOrCodeBlock(RetrievalCandidate candidate) {
        if (candidate == null || candidate.content() == null) {
            return false;
        }

        Map<String, Object> meta = candidate.metadata();
        if (meta != null) {
            if (Boolean.TRUE.equals(meta.get("isTable")) || "true".equalsIgnoreCase(String.valueOf(meta.get("isTable")))) {
                return true;
            }
            String type = String.valueOf(meta.get("chunkType"));
            if ("TABLE".equalsIgnoreCase(type) || "CODE".equalsIgnoreCase(type)) {
                return true;
            }
        }

        String content = candidate.content().trim();
        if (content.startsWith("```") || content.contains("\n```")) {
            return true;
        }

        String[] lines = content.split("\r?\n");
        int dividerCount = 0;
        int tableRowCandidates = 0;
        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("|") && l.endsWith("|")) {
                tableRowCandidates++;
                if (l.matches("^\\|[\\s\\-:\\|]+\\|$")) {
                    dividerCount++;
                }
            }
        }
        return dividerCount >= 1 || (lines.length >= 2 && tableRowCandidates >= 2 && tableRowCandidates == lines.length);
    }

    public static List<SentenceSpan> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<SentenceSpan> spans = new ArrayList<>();
        int length = text.length();
        int start = 0;
        int quoteDepth = 0;
        int bracketDepth = 0;

        int i = 0;
        while (i < length) {
            char ch = text.charAt(i);

            if (isOpeningQuote(ch)) {
                quoteDepth++;
            } else if (isClosingQuote(ch)) {
                quoteDepth = Math.max(0, quoteDepth - 1);
            } else if (isOpeningBracket(ch)) {
                bracketDepth++;
            } else if (isClosingBracket(ch)) {
                bracketDepth = Math.max(0, bracketDepth - 1);
            }

            boolean isBoundary = false;

            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '!' || ch == '?' || ch == ';') {
                isBoundary = true;
            } else if (ch == '.') {
                boolean isDecimal = (i > 0 && Character.isDigit(text.charAt(i - 1)))
                        && (i + 1 < length && Character.isDigit(text.charAt(i + 1)));
                if (!isDecimal) {
                    if (i + 1 == length || Character.isWhitespace(text.charAt(i + 1))
                            || isClosingQuote(text.charAt(i + 1)) || isClosingBracket(text.charAt(i + 1))) {
                        isBoundary = true;
                    }
                }
            } else if (ch == '\n') {
                if (bracketDepth == 0 && quoteDepth == 0) {
                    isBoundary = true;
                }
            }

            if (isBoundary) {
                int end = i + 1;
                while (end < length) {
                    char nextCh = text.charAt(end);
                    if (isClosingQuote(nextCh) || isClosingBracket(nextCh)) {
                        if (isClosingQuote(nextCh)) quoteDepth = Math.max(0, quoteDepth - 1);
                        if (isClosingBracket(nextCh)) bracketDepth = Math.max(0, bracketDepth - 1);
                        end++;
                    } else {
                        break;
                    }
                }

                if (quoteDepth == 0 && bracketDepth == 0) {
                    String raw = text.substring(start, end);
                    String trimmed = raw.trim();
                    if (!trimmed.isEmpty()) {
                        spans.add(new SentenceSpan(spans.size(), start, end, trimmed));
                    }
                    while (end < length && Character.isWhitespace(text.charAt(end))) {
                        end++;
                    }
                    start = end;
                    i = end - 1;
                }
            }

            i++;
        }

        if (start < length) {
            String remaining = text.substring(start).trim();
            if (!remaining.isEmpty()) {
                spans.add(new SentenceSpan(spans.size(), start, length, remaining));
            }
        }

        return spans;
    }

    private static boolean isOpeningQuote(char c) {
        return c == '“' || c == '‘' || c == '"' || c == '\'' || c == '「' || c == '『';
    }

    private static boolean isClosingQuote(char c) {
        return c == '”' || c == '’' || c == '"' || c == '\'' || c == '」' || c == '』';
    }

    private static boolean isOpeningBracket(char c) {
        return c == '(' || c == '（' || c == '[' || c == '【' || c == '《' || c == '{';
    }

    private static boolean isClosingBracket(char c) {
        return c == ')' || c == '）' || c == ']' || c == '】' || c == '》' || c == '}';
    }

    public record SentenceSpan(int index, int start, int end, String text) {}

    private record PartitionKey(UUID documentId, String bodySnapshotId, String heading) {
        static PartitionKey of(RetrievalCandidate c) {
            String snapshot = c.metadata().get("bodySnapshotId") != null
                    ? String.valueOf(c.metadata().get("bodySnapshotId"))
                    : "NO_SNAPSHOT";
            String h = c.heading() != null ? c.heading() : "ROOT";
            return new PartitionKey(c.documentId(), snapshot, h);
        }
    }

    private static class CandidateWithSpan {
        private final RetrievalCandidate candidate;
        private final List<SentenceSpan> sentences;
        private final int windowStart;
        private final int windowEnd;
        private final int matchedStart;
        private final int matchedEnd;

        private CandidateWithSpan(RetrievalCandidate candidate, List<SentenceSpan> sentences,
                                  int windowStart, int windowEnd, int matchedStart, int matchedEnd) {
            this.candidate = candidate;
            this.sentences = sentences;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.matchedStart = matchedStart;
            this.matchedEnd = matchedEnd;
        }
    }
}
