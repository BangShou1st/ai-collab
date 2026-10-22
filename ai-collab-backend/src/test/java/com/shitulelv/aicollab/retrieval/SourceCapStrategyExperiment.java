package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.service.CrossDocumentSourceSelector;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgePromptText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * D-RAG-01C 第二轮 阶段 D：<b>SOURCE_CAP 选择策略对照</b>（实验用，仅存在于测试源码）。
 *
 * <p>本类<b>不进入生产路径</b>：策略 A 直接委托生产 {@link CrossDocumentSourceSelector}；
 * 策略 B 是“相关度保护”实验版本。A 的选择结果经 {@link #assemble} 后由 parity test 与生产
 * {@code buildDiverse} 对齐；B 的选中来源是测试侧反事实，因此相关度收益必须报告为近似实验指标，
 * 不能称作生产上下文的实际提升。</p>
 *
 * <p>策略 B 的规则：保留现有“每文档一个代表”的多样性意图，但加两道相关度保护——
 * ① <b>相对全局第 N 名门槛</b>：某文档的最佳分块若比全局第 maxSources 名的相似度还低，
 * 就不再无条件占用代表名额；② <b>为全局竞争预留名额</b>：代表最多占 maxSources − reserve 个名额，
 * 其余名额一律按全局相似度补齐。这样弱相关文档代表不会无限制地挤掉更相关且互补的证据
 * （当文档数 ≥ 名额数时，旧策略会把全部名额给代表，任何“次强但更相关”的第二分块都进不来）。</p>
 */
final class SourceCapStrategyExperiment {
    private SourceCapStrategyExperiment() {
    }

    /** 实验侧的最小装配预算：与生产一致（12 来源、8000 code point、单条正文下限 120）。 */
    static final int MAX_PROMPT_SOURCE_CODE_POINTS = 8000;
    static final int MIN_SOURCE_BODY_CODE_POINTS = 120;
    private static final int MAX_PROMPT_FILENAME_CODE_POINTS = 180;
    private static final int MAX_PROMPT_HEADING_CODE_POINTS = 300;

    /** 策略 B 为全局竞争预留的名额数（maxSources 的六分之一，至少 1）。 */
    static int globalFillReserve(int maxSources) {
        return Math.max(1, maxSources / 6);
    }

    /** 策略 A：生产既定策略（两阶段：先每文档一个代表，再按全局相似度补齐）。 */
    static List<DocumentSearchHit> selectCurrent(List<DocumentSearchHit> hits, int maxSources) {
        return new CrossDocumentSourceSelector().select(hits, maxSources);
    }

    /** 策略 B：相关度保护的多样性选择（相对全局第 N 名门槛）。 */
    static List<DocumentSearchHit> selectRelevanceProtected(List<DocumentSearchHit> hits, int maxSources) {
        if (hits == null || hits.isEmpty() || maxSources < 1) return List.of();
        // 与生产选择器同源：先过滤不合格候选，再按相似度排序、按文档内容哈希去重。
        List<DocumentSearchHit> eligible = new ArrayList<>();
        for (DocumentSearchHit hit : hits) {
            if (hit == null) continue;
            if (hit.similarity() < KnowledgeContextBuilder.MIN_SIMILARITY) continue;
            if (hit.content() == null || hit.content().isBlank()) continue;
            eligible.add(hit);
        }
        eligible.sort(Comparator
                .comparingDouble(DocumentSearchHit::similarity).reversed()
                .thenComparing(DocumentSearchHit::id));

        Map<UUID, List<DocumentSearchHit>> byDocument = new LinkedHashMap<>();
        Map<UUID, Set<String>> hashesByDocument = new HashMap<>();
        List<DocumentSearchHit> unique = new ArrayList<>();
        for (DocumentSearchHit hit : eligible) {
            Set<String> hashes = hashesByDocument.computeIfAbsent(hit.documentId(), ignored -> new HashSet<>());
            String hash = hit.contentHash();
            if (hash != null && !hashes.add(hash)) continue;
            unique.add(hit);
            byDocument.computeIfAbsent(hit.documentId(), ignored -> new ArrayList<>()).add(hit);
        }

        // 相对全局第 N 名门槛：N = maxSources；候选不足 N 时门槛为 0（不设额外限制）。
        double threshold = unique.size() >= maxSources
                ? unique.get(maxSources - 1).similarity() : 0d;

        List<DocumentSearchHit> selected = new ArrayList<>();
        Set<UUID> selectedIds = new LinkedHashSet<>();
        int representativeCap = maxSources - globalFillReserve(maxSources);
        // 第一阶段：只有相似度不低于全局第 N 名的文档代表才占用代表名额，且代表总数有上限。
        for (List<DocumentSearchHit> documentHits : byDocument.values()) {
            if (selected.size() >= representativeCap) break;
            DocumentSearchHit representative = documentHits.getFirst();
            if (representative.similarity() < threshold) continue;
            selected.add(representative);
            selectedIds.add(representative.id());
        }
        // 第二阶段：剩余名额一律按全局相似度补齐（弱文档仍可凭全局分数进入）。
        for (DocumentSearchHit hit : unique) {
            if (selected.size() == maxSources) break;
            if (selectedIds.add(hit.id())) selected.add(hit);
        }
        return selected;
    }

    /**
     * 策略 C：保守折中方案（测试侧候选选择模拟）。
     *
     * <p>目标：在彻底保留 D-RAG-01B 跨文档覆盖保证（E6 必须通过，次文档代表不被高分主文档挤掉）
     * 的前提下，消除弱相关文档代表对主文档高价值第二分块的过度挤占（解决 E1、真实基线 #2 与 #15）。</p>
     *
     * <p>规则：
     * 1. 当候选文档数不超过 maxSources 时，所有文档代表天然均有充足名额，不设代表上限与相对阈值，
     *    完整保留每个文档的最高代表分块（确保 E6 次文档覆盖 100% 成立）；
     * 2. 当候选文档数超过 maxSources 时，代表名额上限设为 maxSources - reserve（reserve = max(1, maxSources / 6)），
     *    优先按文档最佳分块的相似度选出前 representativeCap 个文档的代表；
     * 3. 剩余名额一律按全局相似度补齐，使主文档的高分第二分块（如 E1 的 0.59、#2 的 rank 8、#15 的 rank 6）
     *    得以通过全局竞争进入上下文。</p>
     */
    static List<DocumentSearchHit> selectConservativeCompromise(List<DocumentSearchHit> hits, int maxSources) {
        if (hits == null || hits.isEmpty() || maxSources < 1) return List.of();
        List<DocumentSearchHit> eligible = new ArrayList<>();
        for (DocumentSearchHit hit : hits) {
            if (hit == null) continue;
            if (hit.similarity() < KnowledgeContextBuilder.MIN_SIMILARITY) continue;
            if (hit.content() == null || hit.content().isBlank()) continue;
            eligible.add(hit);
        }
        eligible.sort(Comparator
                .comparingDouble(DocumentSearchHit::similarity).reversed()
                .thenComparing(DocumentSearchHit::id));

        Map<UUID, List<DocumentSearchHit>> byDocument = new LinkedHashMap<>();
        Map<UUID, Set<String>> hashesByDocument = new HashMap<>();
        List<DocumentSearchHit> unique = new ArrayList<>();
        for (DocumentSearchHit hit : eligible) {
            Set<String> hashes = hashesByDocument.computeIfAbsent(hit.documentId(), ignored -> new HashSet<>());
            String hash = hit.contentHash();
            if (hash != null && !hashes.add(hash)) continue;
            unique.add(hit);
            byDocument.computeIfAbsent(hit.documentId(), ignored -> new ArrayList<>()).add(hit);
        }

        List<DocumentSearchHit> selected = new ArrayList<>();
        Set<UUID> selectedIds = new LinkedHashSet<>();
        // 如果候选文档数严格小于 maxSources（如 E6：2 文档 < 3 名额），
        // 则每个文档代表都能入选，且自然留出至少 1 个名额给全局补齐（主文档第二分块），次文档与主文档兼得；
        // 如果候选文档数 >= maxSources（如 E1：13 文档 >= 12 名额；真实基线 #2/#15：12 文档 >= 12 名额），
        // 则绝不能把名额全部分配给单文档代表，必须将代表上限限制为 maxSources - reserve，
        // 确保至少 reserve 个名额留给全局高分竞争，避免高相关第二分块被弱代表挤占。
        int representativeCap = byDocument.size() < maxSources
                ? byDocument.size()
                : maxSources - globalFillReserve(maxSources);

        for (List<DocumentSearchHit> documentHits : byDocument.values()) {
            if (selected.size() >= representativeCap) break;
            DocumentSearchHit representative = documentHits.getFirst();
            selected.add(representative);
            selectedIds.add(representative.id());
        }

        for (DocumentSearchHit hit : unique) {
            if (selected.size() == maxSources) break;
            if (selectedIds.add(hit.id())) selected.add(hit);
        }
        return selected;
    }

    /** 装配结果：保留的分块/文档身份、上下文字符预算占用与来源数。 */
    record Assembled(List<UUID> chunkIds, Set<UUID> documentIds, int promptCodePoints, int sourceCount) {}

    /** Mirrors production buildDiverse assembly; SourceCapStrategyExperimentParityTest locks parity for A. */
    static Assembled assemble(List<DocumentSearchHit> ordered, int maxSources) {
        if (maxSources < 1 || maxSources > KnowledgeContextBuilder.MAX_SOURCES_LIMIT) {
            throw new IllegalArgumentException("maxSources 超出生产范围");
        }
        List<UUID> retainedChunks = new ArrayList<>();
        Set<UUID> retainedDocuments = new LinkedHashSet<>();
        int remaining = MAX_PROMPT_SOURCE_CODE_POINTS;
        for (int index = 0; index < ordered.size() && retainedChunks.size() < maxSources; index++) {
            DocumentSearchHit hit = ordered.get(index);
            String separator = retainedChunks.isEmpty() ? "" : "\n\n";
            String filename = KnowledgePromptText.escapeXmlText(
                    hit.originalFilename(), MAX_PROMPT_FILENAME_CODE_POINTS).escaped();
            String heading = KnowledgePromptText.escapeXmlText(
                    hit.heading(), MAX_PROMPT_HEADING_CODE_POINTS).escaped();
            String prefix = separator
                    + "[S" + (retainedChunks.size() + 1) + "]\n"
                    + "文件：" + filename + "\n"
                    + "标题：" + heading + "\n"
                    + "内容：";
            int prefixLength = prefix.codePointCount(0, prefix.length());
            if (prefixLength >= remaining) break;
            int available = remaining - prefixLength;
            int sourcesLeft = Math.max(1, ordered.size() - index);
            int allowance = available / sourcesLeft;
            if (allowance < MIN_SOURCE_BODY_CODE_POINTS) break;
            KnowledgePromptText.EscapedText content = KnowledgePromptText.escapeXmlText(hit.content(), allowance);
            if (content.original().isBlank()) continue;
            int bodyCodePoints = content.escaped().codePointCount(0, content.escaped().length());
            remaining -= prefixLength + bodyCodePoints;
            retainedChunks.add(hit.id());
            retainedDocuments.add(hit.documentId());
        }
        return new Assembled(List.copyOf(retainedChunks), retainedDocuments,
                MAX_PROMPT_SOURCE_CODE_POINTS - remaining, retainedChunks.size());
    }

}
