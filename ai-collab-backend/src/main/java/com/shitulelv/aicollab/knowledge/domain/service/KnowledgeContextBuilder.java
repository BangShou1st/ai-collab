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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class KnowledgeContextBuilder {
    public static final double MIN_SIMILARITY = 0.15d;
    /** Knowledge Q&A keeps its established default source limit. */
    public static final int DEFAULT_MAX_SOURCES = 5;
    /** Public source limit shared with the bounded Agent tool. */
    public static final int MAX_SOURCES_LIMIT = 12;
    private static final int MAX_PROMPT_SOURCE_CODE_POINTS = 8000;
    private static final int MAX_PROMPT_FILENAME_CODE_POINTS = 180;
    private static final int MAX_PROMPT_HEADING_CODE_POINTS = 300;
    private static final int MIN_SOURCE_BODY_CODE_POINTS = 120;

    private final CrossDocumentSourceSelector selector = new CrossDocumentSourceSelector();

    /** Knowledge Q&A entry: at most five sources and an 8000 code point prompt budget. */
    public KnowledgeContext build(List<DocumentSearchHit> hits) {
        return build(hits, DEFAULT_MAX_SOURCES);
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
                    hit.content(), remaining - prefixLength);
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
                    hit.content(), allowance);
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
