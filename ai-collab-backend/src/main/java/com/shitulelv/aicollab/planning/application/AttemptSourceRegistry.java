package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.planning.domain.PlanSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 单次规划详情生成的动态证据来源注册表（线程安全、有界、去重）。
 *
 * <p>以初始 {@code PlanningContext.sources()}（{@code S1..Sk}）为起点，允许详情阶段只读检索工具
 * （{@code search_project_knowledge}）在预算内把新发现的分片动态登记为 {@code S(k+1)..Sn}，
 * 并在最终草稿校验与版本提交时作为唯一的权威 {@code sources} 清单。</p>
 */
public final class AttemptSourceRegistry {

    /** 对齐 {@code TaskPlanDraftValidator} 的 {@code S1..S12} 领域上限，避免动态登记触发校验硬伤。 */
    public static final int HARD_MAX_SOURCES = 12;

    private final int maxSources;
    private int remainingCodepoints;
    private final List<PlanSource> sources = new ArrayList<>();
    private final Map<String, PlanSource> byHash = new HashMap<>();
    private final Map<String, PlanSource> byDocChunk = new HashMap<>();
    private final Set<String> usedRefs = new HashSet<>();

    public AttemptSourceRegistry(List<PlanSource> initialSources, int maxSources, int maxTotalCodepoints) {
        int initialCount = initialSources == null ? 0 : initialSources.size();
        this.maxSources = Math.min(HARD_MAX_SOURCES, Math.max(initialCount, Math.max(1, maxSources)));
        int usedCodepoints = 0;
        if (initialSources != null) {
            for (PlanSource source : initialSources) {
                if (source == null || source.ref() == null) {
                    continue;
                }
                sources.add(source);
                usedRefs.add(source.ref());
                if (source.contentHash() != null && !source.contentHash().isBlank()) {
                    byHash.putIfAbsent(source.contentHash().trim(), source);
                }
                if (source.documentId() != null && source.chunkId() != null) {
                    byDocChunk.putIfAbsent(docChunkKey(source.documentId(), source.chunkId()), source);
                }
                if (source.quoteText() != null) {
                    usedCodepoints += source.quoteText().codePointCount(0, source.quoteText().length());
                }
            }
        }
        this.remainingCodepoints = Math.max(0, maxTotalCodepoints - usedCodepoints);
    }

    /** 将检索命中登记为规范来源；若已在当前尝试中存在则返回既有引用，超出容量/预算则返回空。 */
    public synchronized Optional<PlanSource> register(DocumentSearchHit hit) {
        if (hit == null || hit.documentId() == null || hit.content() == null || hit.content().isBlank()) {
            return Optional.empty();
        }
        String hashKey = hit.contentHash() == null ? null : hit.contentHash().trim();
        if (hashKey != null && !hashKey.isBlank()) {
            PlanSource existingByHash = byHash.get(hashKey);
            if (existingByHash != null) {
                return Optional.of(existingByHash);
            }
        }
        if (hit.id() != null) {
            PlanSource existingByChunk = byDocChunk.get(docChunkKey(hit.documentId(), hit.id()));
            if (existingByChunk != null) {
                return Optional.of(existingByChunk);
            }
        }
        if (sources.size() >= maxSources) {
            return Optional.empty();
        }
        int contentCodepoints = hit.content().codePointCount(0, hit.content().length());
        if (contentCodepoints > remainingCodepoints) {
            return Optional.empty();
        }
        int ordinal = sources.size() + 1;
        while (usedRefs.contains("S" + ordinal)) {
            ordinal++;
        }
        String ref = "S" + ordinal;
        PlanSource source = new PlanSource(
                ref,
                hit.documentId(),
                hit.originalFilename(),
                hit.id(),
                hit.heading(),
                hit.similarity(),
                hit.content(),
                hashKey);
        sources.add(source);
        usedRefs.add(ref);
        if (hashKey != null && !hashKey.isBlank()) {
            byHash.put(hashKey, source);
        }
        if (hit.id() != null) {
            byDocChunk.put(docChunkKey(hit.documentId(), hit.id()), source);
        }
        remainingCodepoints -= contentCodepoints;
        return Optional.of(source);
    }

    public synchronized List<PlanSource> snapshot() {
        return List.copyOf(sources);
    }

    public int maxSources() {
        return maxSources;
    }

    private static String docChunkKey(UUID documentId, UUID chunkId) {
        return documentId + ":" + chunkId;
    }
}
