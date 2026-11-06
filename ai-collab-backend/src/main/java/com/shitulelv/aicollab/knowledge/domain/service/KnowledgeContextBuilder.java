package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class KnowledgeContextBuilder {
    /**
     * 准入阈值：作用于 {@code DocumentSearchHit.similarity} 的<b>准入分数</b>语义——
     * Dense 余弦相似度或 Lexical 匹配强度（两类 [0,1] 可比分数的较大者，见
     * {@code com.shitulelv.aicollab.common.retrieval.RetrievalScores} 契约）。
     * RRF 融合分与重排分是排序语义，绝不进入该字段；因此本阈值不得用于拦截
     * “分数类型被改写”的候选——类型化契约在管线输出层已保证字段语义正确。
     * 全部装配入口（buildWithTokenBudget / buildBalancedWithDiagnostics / buildWithDiagnostics /
     * buildDiverseWithDiagnostics / classifyCandidatesAfterBudgetStop）共享同一准入语义。
     */
    public static final double MIN_SIMILARITY = 0.15d;
    /** Knowledge Q&A keeps its established default source limit. */
    public static final int DEFAULT_MAX_SOURCES = 5;
    /** Public source limit shared with the bounded Agent tool. */
    public static final int MAX_SOURCES_LIMIT = 12;
    /**
     * Knowledge Q&A 每文档来源名额均衡上限（P6-RAG-QUALITY-01 S2）：
     * 候选顺序装配前，单份文档在第一遍最多占用 2 个来源名额，防止单文档候选洪泛
     * 挤掉其他文档的关键事实（历史 Q03：上线门禁候选第 6 名被同文档名额挤掉）。
     * 只使用候选的 documentId 身份信号，不做标题/正文字面匹配。
     */
    public static final int PER_DOCUMENT_SOURCE_CAP = 2;
    private static final int MAX_PROMPT_SOURCE_CODE_POINTS = 8000;
    private static final int MAX_PROMPT_FILENAME_CODE_POINTS = 180;
    private static final int MAX_PROMPT_HEADING_CODE_POINTS = 300;
    private static final int MIN_SOURCE_BODY_CODE_POINTS = 120;

    private final CrossDocumentSourceSelector selector = new CrossDocumentSourceSelector();

    /** Knowledge Q&A entry: at most five sources and an 8000 code point prompt budget. */
    public KnowledgeContext build(List<DocumentSearchHit> hits) {
        return build(hits, DEFAULT_MAX_SOURCES);
    }

    /**
     * Knowledge Q&A balanced entry（生产 QA 入口）：候选顺序 + 每文档名额均衡后走既有装配。
     * 名额、预算、候选集合与用户 scope 均不变；候选数不超过名额时与 {@link #build(List)} 完全一致。
     */
    public KnowledgeContext buildBalanced(List<DocumentSearchHit> hits) {
        return buildBalancedWithDiagnostics(hits, DEFAULT_MAX_SOURCES).context();
    }

    public BuildResult buildBalancedWithDiagnostics(List<DocumentSearchHit> hits, int maxSources) {
        validateMaxSources(maxSources);
        return buildWithDiagnostics(
                reorderPerDocumentCap(hits, maxSources, PER_DOCUMENT_SOURCE_CAP), maxSources);
    }

    /**
     * 基于 TokenCounter 精确预算的证据装配入口：
     * 结合每文档名额均衡、单来源正文与父级片段有界融入、精确 Token 预算防护及完整丢弃原因诊断。
     */
    public BuildResult buildWithTokenBudget(
            List<DocumentSearchHit> hits,
            int maxSources,
            int tokenBudget,
            com.shitulelv.aicollab.common.ai.token.TokenCounter tokenCounter,
            String provider,
            String model) {
        validateMaxSources(maxSources);
        List<DocumentSearchHit> ordered = reorderPerDocumentCap(hits, maxSources, PER_DOCUMENT_SOURCE_CAP);
        List<KnowledgeSource> sources = new ArrayList<>();
        Set<UUID> retainedChunkIds = new HashSet<>();
        Map<UUID, Set<String>> hashesByDocument = new HashMap<>();
        Map<UUID, ContextDropReason> dropped = new LinkedHashMap<>();
        StringBuilder prompt = new StringBuilder();

        int usedTokens = 0;
        int maxTokens = tokenBudget > 0 ? tokenBudget : 2500; // 默认给证据预留 2500 tokens

        for (int index = 0; index < ordered.size(); index++) {
            DocumentSearchHit hit = ordered.get(index);
            if (hit.similarity() < MIN_SIMILARITY) {
                dropped.put(hit.id(), ContextDropReason.BELOW_SIMILARITY_THRESHOLD);
                continue;
            }
            if (sources.size() >= maxSources) {
                dropped.put(hit.id(), ContextDropReason.SOURCE_CAP);
                continue;
            }
            String hash = hit.contentHash();
            Set<String> documentHashes = hashesByDocument.get(hit.documentId());
            if (retainedChunkIds.contains(hit.id())
                    || (hash != null && documentHashes != null && documentHashes.contains(hash))) {
                dropped.put(hit.id(), ContextDropReason.INTRA_DOCUMENT_DUPLICATE);
                continue;
            }
            if (hit.content() == null || hit.content().isBlank()) {
                dropped.put(hit.id(), ContextDropReason.EMPTY_BODY);
                continue;
            }

            int rank = sources.size() + 1;
            String separator = prompt.isEmpty() ? "" : "\n\n";
            String filename = KnowledgePromptText.escapeXmlText(
                    hit.originalFilename(), MAX_PROMPT_FILENAME_CODE_POINTS).escaped();
            String heading = KnowledgePromptText.escapeXmlText(
                    hit.heading(), MAX_PROMPT_HEADING_CODE_POINTS).escaped();

            // 检查是否有父级结构摘要 (Parent-Child)
            String parentContext = "";
            if (hit.metadata() != null && hit.metadata().containsKey("parentSnippet")) {
                Object pSnippet = hit.metadata().get("parentSnippet");
                if (pSnippet instanceof String pStr && !pStr.isBlank() && !pStr.equals(hit.content())) {
                    parentContext = "【章节背景】" + KnowledgePromptText.escapeXmlText(pStr, 200).escaped() + "\n";
                }
            }

            // 优先检查并使用扩展后的句子窗口上下文 (Sentence Window Retrieval)，防止上下文丢失
            String bodyContent = resolveEffectiveBodyContent(hit);

            String sourceBlock = separator
                    + "[S" + rank + "]\n"
                    + "文件：" + filename + "\n"
                    + "标题：" + heading + "\n"
                    + parentContext
                    + "内容：" + KnowledgePromptText.escapeXmlText(bodyContent, 1500).escaped();

            int blockTokens = tokenCounter != null
                    ? tokenCounter.countText(sourceBlock, provider, model).count()
                    : (codePointLength(sourceBlock) + 2) / 3;

            if (usedTokens + blockTokens > maxTokens && !sources.isEmpty()) {
                dropped.put(hit.id(), ContextDropReason.TOKEN_BUDGET_EXCEEDED);
                continue;
            }

            prompt.append(sourceBlock);
            usedTokens += blockTokens;
            sources.add(new KnowledgeSource(
                    hit.id(), hit.documentId(), hit.originalFilename(), hit.heading(),
                    hit.content(), hit.contentHash(), hit.similarity(), rank,
                    hit.metadata() != null ? hit.metadata() : Map.of()));
            retainedChunkIds.add(hit.id());
            if (hash != null) {
                hashesByDocument.computeIfAbsent(hit.documentId(), ignored -> new HashSet<>()).add(hash);
            }
        }

        return new BuildResult(new KnowledgeContext(List.copyOf(sources), prompt.toString()), dropped);
    }

    /**
     * 每文档名额均衡的两遍重排：第一遍按候选顺序为每文档至多取 cap 条；第二遍按候选顺序补齐剩余名额。
     * 返回全量候选的重排（均衡入选者在前、其余候选按原顺序在后），因此装配阶段的
     * 阈值/同文去重/预算淘汰仍保留后备候选回填语义。候选数不超过名额时恒等返回原顺序。
     */
    private static List<DocumentSearchHit> reorderPerDocumentCap(
            List<DocumentSearchHit> hits, int maxSources, int perDocumentCap) {
        if (hits.size() <= maxSources) {
            return hits;
        }
        List<DocumentSearchHit> balanced = new ArrayList<>(maxSources);
        Map<UUID, Integer> usedByDocument = new HashMap<>();
        Set<UUID> selected = new LinkedHashSet<>();
        for (DocumentSearchHit hit : hits) {
            if (balanced.size() == maxSources) break;
            int used = usedByDocument.getOrDefault(hit.documentId(), 0);
            if (used < perDocumentCap) {
                usedByDocument.put(hit.documentId(), used + 1);
                balanced.add(hit);
                selected.add(hit.id());
            }
        }
        for (DocumentSearchHit hit : hits) {
            if (balanced.size() == maxSources) break;
            if (selected.add(hit.id())) {
                balanced.add(hit);
            }
        }
        List<DocumentSearchHit> reordered = new ArrayList<>(hits.size());
        reordered.addAll(balanced);
        for (DocumentSearchHit hit : hits) {
            if (!selected.contains(hit.id())) reordered.add(hit);
        }
        return reordered;
    }

    /** The legacy entry preserves main's per-candidate filtering, deduplication and assembly order. */
    public KnowledgeContext build(List<DocumentSearchHit> hits, int maxSources) {
        return buildWithDiagnostics(hits, maxSources).context();
    }

    /**
     * Builds the legacy context and records the concrete reason for each omitted candidate.
     * The assembly loop intentionally retains the established per-candidate mutation order:
     * a hash becomes used only after that candidate is actually written into the prompt.
     */
    public BuildResult buildWithDiagnostics(List<DocumentSearchHit> hits, int maxSources) {
        validateMaxSources(maxSources);
        List<KnowledgeSource> sources = new ArrayList<>();
        Set<UUID> retainedChunkIds = new HashSet<>();
        Map<UUID, Set<String>> hashesByDocument = new HashMap<>();
        Map<UUID, ContextDropReason> dropped = new LinkedHashMap<>();
        StringBuilder prompt = new StringBuilder();
        int remaining = MAX_PROMPT_SOURCE_CODE_POINTS;

        for (int index = 0; index < hits.size(); index++) {
            DocumentSearchHit hit = hits.get(index);
            if (hit.similarity() < MIN_SIMILARITY) {
                dropped.put(hit.id(), ContextDropReason.BELOW_SIMILARITY_THRESHOLD);
                continue;
            }
            if (sources.size() >= maxSources) {
                dropped.put(hit.id(), ContextDropReason.SOURCE_CAP);
                continue;
            }
            String hash = hit.contentHash();
            Set<String> documentHashes = hashesByDocument.get(hit.documentId());
            if (retainedChunkIds.contains(hit.id())
                    || (hash != null && documentHashes != null && documentHashes.contains(hash))) {
                dropped.put(hit.id(), ContextDropReason.INTRA_DOCUMENT_DUPLICATE);
                continue;
            }
            if (hit.content() == null || hit.content().isBlank()) {
                dropped.put(hit.id(), ContextDropReason.EMPTY_BODY);
                continue;
            }

            int rank = sources.size() + 1;
            String separator = prompt.isEmpty() ? "" : "\n\n";
            String filename = KnowledgePromptText.escapeXmlText(
                    hit.originalFilename(), MAX_PROMPT_FILENAME_CODE_POINTS).escaped();
            String heading = KnowledgePromptText.escapeXmlText(
                    hit.heading(), MAX_PROMPT_HEADING_CODE_POINTS).escaped();
            String prefix = separator
                    + "[S" + rank + "]\n"
                    + "文件：" + filename + "\n"
                    + "标题：" + heading + "\n"
                    + "内容：";
            int prefixLength = codePointLength(prefix);
            if (prefixLength >= remaining) {
                dropped.put(hit.id(), ContextDropReason.BODY_BUDGET);
                classifyCandidatesAfterBudgetStop(
                        hits, index + 1, maxSources, sources.size(), retainedChunkIds, hashesByDocument, dropped);
                break;
            }

            KnowledgePromptText.EscapedText content = KnowledgePromptText.escapeXmlText(
                    resolveEffectiveBodyContent(hit), remaining - prefixLength);
            if (content.original().isBlank()) {
                dropped.put(hit.id(), ContextDropReason.BODY_BUDGET);
                continue;
            }

            prompt.append(prefix).append(content.escaped());
            remaining -= prefixLength + codePointLength(content.escaped());
            sources.add(new KnowledgeSource(
                    hit.id(), hit.documentId(), hit.originalFilename(), hit.heading(),
                    content.original(), hit.contentHash(), hit.similarity(), rank,
                    hit.metadata() != null ? hit.metadata() : Map.of()));
            retainedChunkIds.add(hit.id());
            if (hash != null) {
                hashesByDocument.computeIfAbsent(hit.documentId(), ignored -> new HashSet<>()).add(hash);
            }
        }
        return new BuildResult(new KnowledgeContext(List.copyOf(sources), prompt.toString()), dropped);
    }

    /** Cross-document Agent entry with two-stage selection and a fair body budget. */
    public KnowledgeContext buildDiverse(List<DocumentSearchHit> hits, int maxSources) {
        return buildDiverseWithDiagnostics(hits, maxSources).context();
    }

    public BuildResult buildDiverseWithDiagnostics(List<DocumentSearchHit> hits, int maxSources) {
        validateMaxSources(maxSources);
        CrossDocumentSourceSelector.SelectionResult selection = selector.selectWithDiagnostics(hits, maxSources);
        Map<UUID, ContextDropReason> dropped = new LinkedHashMap<>(selection.droppedReasons());
        List<DocumentSearchHit> ordered = selection.hits();
        List<KnowledgeSource> sources = new ArrayList<>();
        StringBuilder prompt = new StringBuilder();
        int remaining = MAX_PROMPT_SOURCE_CODE_POINTS;

        for (int index = 0; index < ordered.size() && sources.size() < maxSources; index++) {
            DocumentSearchHit hit = ordered.get(index);
            int rank = sources.size() + 1;
            String separator = prompt.isEmpty() ? "" : "\n\n";
            String filename = KnowledgePromptText.escapeXmlText(
                    hit.originalFilename(), MAX_PROMPT_FILENAME_CODE_POINTS).escaped();
            String heading = KnowledgePromptText.escapeXmlText(
                    hit.heading(), MAX_PROMPT_HEADING_CODE_POINTS).escaped();
            String prefix = separator
                    + "[S" + rank + "]\n"
                    + "文件：" + filename + "\n"
                    + "标题：" + heading + "\n"
                    + "内容：";
            int prefixLength = codePointLength(prefix);
            if (prefixLength >= remaining) {
                markRemainingAsBudgetDrop(ordered, index, dropped);
                break;
            }
            int available = remaining - prefixLength;
            int sourcesLeft = Math.max(1, ordered.size() - index);
            int allowance = available / sourcesLeft;
            if (allowance < MIN_SOURCE_BODY_CODE_POINTS) {
                markRemainingAsBudgetDrop(ordered, index, dropped);
                break;
            }

            KnowledgePromptText.EscapedText content = KnowledgePromptText.escapeXmlText(
                    resolveEffectiveBodyContent(hit), allowance);
            if (content.original().isBlank()) {
                dropped.put(hit.id(), ContextDropReason.BODY_BUDGET);
                continue;
            }

            prompt.append(prefix).append(content.escaped());
            remaining -= prefixLength + codePointLength(content.escaped());
            sources.add(new KnowledgeSource(
                    hit.id(), hit.documentId(), hit.originalFilename(), hit.heading(),
                    content.original(), hit.contentHash(), hit.similarity(), rank,
                    hit.metadata() != null ? hit.metadata() : Map.of()));
        }

        Set<UUID> retainedIds = new HashSet<>();
        for (KnowledgeSource source : sources) retainedIds.add(source.chunkId());
        for (DocumentSearchHit hit : ordered) {
            if (!retainedIds.contains(hit.id())) {
                dropped.putIfAbsent(hit.id(), ContextDropReason.UNATTRIBUTED);
            }
        }
        return new BuildResult(new KnowledgeContext(List.copyOf(sources), prompt.toString()), dropped);
    }

    private static void classifyCandidatesAfterBudgetStop(
            List<DocumentSearchHit> hits,
            int start,
            int maxSources,
            int retainedSourceCount,
            Set<UUID> retainedChunkIds,
            Map<UUID, Set<String>> hashesByDocument,
            Map<UUID, ContextDropReason> dropped) {
        for (int index = start; index < hits.size(); index++) {
            DocumentSearchHit hit = hits.get(index);
            Set<String> documentHashes = hashesByDocument.get(hit.documentId());
            if (hit.similarity() < MIN_SIMILARITY) {
                dropped.put(hit.id(), ContextDropReason.BELOW_SIMILARITY_THRESHOLD);
            } else if (retainedSourceCount >= maxSources) {
                dropped.put(hit.id(), ContextDropReason.SOURCE_CAP);
            } else if (retainedChunkIds.contains(hit.id())
                    || (hit.contentHash() != null && documentHashes != null && documentHashes.contains(hit.contentHash()))) {
                dropped.put(hit.id(), ContextDropReason.INTRA_DOCUMENT_DUPLICATE);
            } else if (hit.content() == null || hit.content().isBlank()) {
                dropped.put(hit.id(), ContextDropReason.EMPTY_BODY);
            } else {
                dropped.put(hit.id(), ContextDropReason.BODY_BUDGET);
            }
        }
    }

    private static void markRemainingAsBudgetDrop(
            List<DocumentSearchHit> hits, int start, Map<UUID, ContextDropReason> dropped) {
        for (int index = start; index < hits.size(); index++) {
            dropped.putIfAbsent(hits.get(index).id(), ContextDropReason.BODY_BUDGET);
        }
    }

    private static void validateMaxSources(int maxSources) {
        if (maxSources < 1 || maxSources > MAX_SOURCES_LIMIT) {
            throw new IllegalArgumentException(
                    "maxSources 必须在 1 到 " + MAX_SOURCES_LIMIT + " 之间");
        }
    }

    private static String resolveEffectiveBodyContent(DocumentSearchHit hit) {
        if (hit != null && hit.metadata() != null && hit.metadata().containsKey("expandedContent")) {
            Object exp = hit.metadata().get("expandedContent");
            if (exp instanceof String expStr && !expStr.isBlank()) {
                return expStr;
            }
        }
        return hit != null ? hit.content() : "";
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    public record BuildResult(
            KnowledgeContext context,
            Map<UUID, ContextDropReason> dropReasons) {
        public BuildResult {
            dropReasons = Collections.unmodifiableMap(new LinkedHashMap<>(dropReasons));
        }
    }
}
